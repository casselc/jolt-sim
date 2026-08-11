(ns jolt.sim.workbench-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.trace :as trace]
            [jolt.sim.workbench :as workbench]))

(defn- caught-data [thunk]
  (try (thunk) nil (catch :default error (ex-data error))))

(defn- provenance [coordinate]
  {:producer :test/workbench :coordinate coordinate})

(defn- item-input
  ([id source-revision value]
   (item-input id source-revision value nil nil))
  ([id source-revision value schema-id suggested-kind]
   {:id id :source-revision source-revision :value value
    :schema-id schema-id :suggested-kind suggested-kind
    :provenance (provenance {:test-case id :revision source-revision})}))

(defn- rule-input [id selector kind priority]
  {:id id :selector selector :kind kind :priority priority :enabled? true
   :provenance (provenance {:rule id})})

(deftest source-items-are-immutable-versioned-evidence
  (let [bytes (byte-array [(byte 0) (byte 127) (unchecked-byte 255)])
        d0 (workbench/empty-document)
        d1 (workbench/append-item
            d0 (item-input "response" 0
                           {:kind :demo/http-response :body bytes}
                           :demo/http-response nil))
        stored-before (workbench/item d1 "response" 0)]
    (aset-byte bytes 0 (byte 9))
    (is (= [0 127 -1]
           (vec (:body (trace/restore-value (:value stored-before))))))
    (is (= :demo/http-response (:source-kind stored-before)))
    (is (= :jolt.sim.fingerprint/crc32c-v1
           (get-in stored-before [:source-fingerprint :algorithm])))
    (is (pos? (get-in stored-before [:source-fingerprint :bytes])))
    (is (= 1 (:revision (workbench/snapshot d1))))
    (is (= 1 (:item-count (workbench/snapshot d1))))
    (is (= :duplicate-item-coordinate
           (:reason (caught-data
                     #(workbench/append-item
                       d1 (item-input "response" 0 {:body :again}))))))
    (is (= :nonmonotonic-source-revision
           (:reason (caught-data
                     #(workbench/append-item
                       (workbench/append-item
                        d1 (item-input "response" 2 {:body :newer}))
                       (item-input "response" 1 {:body :older}))))))))

(deftest persisted-rules-and-exact-overrides-have-explicit-precedence
  (let [d1 (workbench/append-item
            (workbench/empty-document)
            (item-input "order-7" 0
                        {:kind :shop/order :status :pending}
                        :shop/order :kind/table))
        d2 (workbench/put-domain-rule
            d1 (rule-input "orders" {:source-kind :shop/order}
                           :kind/tree 10))
        d3 (workbench/put-domain-rule
            d2 (rule-input "schema" {:schema-id :shop/order}
                           :kind/inspector 20))
        fingerprint (:source-fingerprint (workbench/item d3 "order-7" 0))
        d4 (workbench/set-item-kind
            d3 {:item-id "order-7" :source-revision 0
                :source-fingerprint fingerprint :kind :kind/pprint
                :provenance (provenance {:user :alice})})
        encoded (workbench/canonical-edn d4)
        restored (workbench/read-edn encoded)]
    (is (= {:kind :kind/inspector :source :domain-rule :rule-id "schema"}
           (workbench/resolve-kind d3 "order-7" 0)))
    (is (= {:kind :kind/pprint :source :exact-override}
           (workbench/resolve-kind d4 "order-7" 0)))
    (is (= d4 restored))
    (is (= encoded (workbench/canonical-edn restored)))
    (is (= 4 (:revision (workbench/snapshot restored))))
    (is (= 4 (count (:jolt.sim.workbench/journal restored))))
    (let [cleared (workbench/clear-item-kind restored "order-7" 0)]
      (is (= :domain-rule
             (:source (workbench/resolve-kind cleared "order-7" 0))))
      (let [removed (workbench/remove-domain-rule cleared "schema")]
        (is (= "orders"
               (:rule-id (workbench/resolve-kind removed "order-7" 0))))))))

(deftest rule-ties-use-newest-update-then-stable-id
  (let [d1 (workbench/append-item
            (workbench/empty-document)
            (item-input "x" 0 {:kind :demo/value}))
        d2 (workbench/put-domain-rule
            d1 (rule-input "a" {:source-kind :demo/value} :kind/a 5))
        d3 (workbench/put-domain-rule
            d2 (rule-input "b" {:source-kind :demo/value} :kind/b 5))
        d4 (workbench/put-domain-rule
            d3 (rule-input "a" {:source-kind :demo/value} :kind/a2 5))]
    (is (= {:kind :kind/b :source :domain-rule :rule-id "b"}
           (workbench/resolve-kind d3 "x" 0)))
    (is (= {:kind :kind/a2 :source :domain-rule :rule-id "a"}
           (workbench/resolve-kind d4 "x" 0)))))

(deftest producer-default-and-raw-fallback-need-no-registry
  (let [raw (workbench/append-item
             (workbench/empty-document)
             (item-input "raw" 0 {:answer 42}))
        suggested (workbench/append-item
                   raw (item-input "suggested" 0 {:answer 43}
                                   nil :kind/table))]
    (is (= {:kind :jolt.sim.kind/raw-value :source :raw}
           (workbench/resolve-kind raw "raw" 0)))
    (is (= {:kind :kind/table :source :producer-default}
           (workbench/resolve-kind suggested "suggested" 0)))))

(deftest persisted-selection-drives-a-kind-keyed-presenter
  (let [d1 (workbench/append-item
            (workbench/empty-document)
            (item-input "orders" 0
                        [{:id 1 :total 10} {:id 2 :total 20}]
                        :shop/orders nil))
        d2 (workbench/put-domain-rule
            d1 (rule-input "order-table" {:schema-id :shop/orders}
                           :kind/table 10))
        rendered
        (workbench/present-item
         d2
         {:kind/table
          {:present (fn [value]
                      {:summary "Orders"
                       :fields [{:label "Count" :value (count value)}]})}}
         "orders" 0)]
    (is (= {:item-id "orders" :source-revision 0}
           (:coordinate rendered)))
    (is (= {:kind :kind/table :source :domain-rule
            :rule-id "order-table"}
           (:selection rendered)))
    (is (= :kind/table (get-in rendered [:presentation :kind])))
    (is (= "Orders" (get-in rendered [:presentation :summary])))
    (is (= 2 (-> rendered :presentation :fields first :value
                 trace/restore-value)))
    (is (= presentation/invalid-kind-registry
           (:type (caught-data
                   #(workbench/present-item d2 {} "orders" 0)))))
    (is (= d2 (workbench/read-edn (workbench/canonical-edn d2))))))

(deftest stale-overrides-and-unknown-removals-fail-before-journal-change
  (let [d1 (workbench/append-item
            (workbench/empty-document)
            (item-input "x" 0 {:kind :demo/value}))
        source (workbench/item d1 "x" 0)
        stale (update-in (:source-fingerprint source) [:crc32c] bit-xor 1)
        stale-data
        (caught-data
         #(workbench/set-item-kind
           d1 {:item-id "x" :source-revision 0
               :source-fingerprint stale :kind :kind/table
               :provenance (provenance {:user :alice})}))]
    (is (= workbench/rejected-command (:type stale-data)))
    (is (= :stale-source (:reason stale-data)))
    (is (= 1 (:revision (workbench/snapshot d1))))
    (is (= :unknown-rule
           (:reason (caught-data #(workbench/remove-domain-rule d1 "none")))))
    (is (= :unknown-override
           (:reason (caught-data #(workbench/clear-item-kind d1 "x" 0)))))))

(deftest persisted-documents-validate-the-entire-journal-fail-closed
  (let [valid (workbench/append-item
               (workbench/empty-document)
               (item-input "x" 0 {:kind :demo/value}))
        corrupt-revision
        (assoc-in valid [:jolt.sim.workbench/journal 0 :revision] 9)
        corrupt-source
        (assoc-in valid
                  [:jolt.sim.workbench/journal 0 :item :source-kind]
                  :demo/not-the-source)]
    (is (= :noncontiguous-revision
           (:reason (caught-data
                     #(workbench/validate-document! corrupt-revision)))))
    (is (= :source-kind-mismatch
           (:reason (caught-data
                     #(workbench/validate-document! corrupt-source)))))
    (doseq [[text reason]
            [["" :unreadable-edn]
             [(str (workbench/canonical-edn valid) " :trailing")
              :trailing-edn]]]
      (testing reason
        (let [data (caught-data #(workbench/read-edn text))]
          (is (= workbench/invalid-document (:type data)))
          (is (= reason (:reason data))))))))
