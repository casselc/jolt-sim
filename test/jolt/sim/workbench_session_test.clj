(ns jolt.sim.workbench-session-test
  (:require [clojure.core.protocols :as protocols]
            [clojure.datafy :as datafy]
            [clojure.test :refer [deftest is]]
            [jolt.sim.trace :as trace]
            [jolt.sim.workbench :as workbench]
            [jolt.sim.workbench-session :as session]))

(defn- caught-data [thunk]
  (try (thunk) nil (catch :default error (ex-data error))))

(defn- provenance [coordinate]
  {:producer :test/session :coordinate coordinate})

(defn- item [id revision value]
  {:id id :source-revision revision :value value
   :schema-id nil :suggested-kind nil
   :provenance (provenance {:item id :revision revision})})

(deftest repl-and-ui-mutations-share-one-persistent-document
  (let [s (session/start
           {:kind-registry
            {:kind/count
             {:present (fn [value]
                         {:summary "Counted"
                          :fields [{:label "Count" :value (count value)}]})}}})]
    (is (= 1 (:revision (session/append-item!
                        s (item "orders" 0 [{:id 1} {:id 2}])))))
    (is (= 2 (:revision
              (session/put-domain-rule!
               s {:id "orders-as-count"
                  :selector {:schema-id :shop/orders}
                  :kind :kind/count :priority 10 :enabled? true
                  :provenance (provenance {:rule :orders})}))))
    ;; The first item has no schema, so the rule is persisted but inapplicable.
    (is (= :jolt.sim.kind/raw-value
           (get-in (session/frame s) [:items 0 :selection :kind])))
    (is (= (session/document s)
           (workbench/read-edn (session/canonical-edn s))))
    (is (= 2 (count (:jolt.sim.workbench/journal (session/document s)))))))

(deftest exact-kind-changes-render-and-export-without-rewriting-source
  (let [s (session/start
           {:kind-registry
            {:kind/count
             {:present (fn [value]
                         {:summary "Counted"
                          :fields [{:label "Count" :value (count value)}]})}}})
        _ (session/append-item! s (item "orders" 0 [1 2 3]))
        before (workbench/item (session/document s) "orders" 0)
        _ (session/set-item-kind!
           s {:item-id "orders" :source-revision 0
              :source-fingerprint (:source-fingerprint before)
              :kind :kind/count
              :provenance (provenance {:user :alice})})
        frame (session/frame s)
        after (workbench/item (session/document s) "orders" 0)]
    (is (= (:value before) (:value after)))
    (is (= :kind/count (get-in frame [:items 0 :selection :kind])))
    (is (= "Counted" (get-in frame [:items 0 :presentation :summary])))
    (is (= 3 (-> frame :items first :presentation :fields first :value
                 trace/restore-value)))
    (is (nil? (get-in frame [:items 0 :presentation-error])))
    (is (= 2 (:revision frame)))))

(deftest missing-renderers-are-advisory-and-source-remains-visible
  (let [document (-> (workbench/empty-document)
                     (workbench/append-item (item "x" 0 {:answer 42})))
        stored (workbench/item document "x" 0)
        document (workbench/set-item-kind
                  document
                  {:item-id "x" :source-revision 0
                   :source-fingerprint (:source-fingerprint stored)
                   :kind :kind/not-installed
                   :provenance (provenance {:user :alice})})
        s (session/start {:document document})
        item-frame (first (:items (session/frame s)))]
    (is (nil? (:presentation item-frame)))
    (is (= :unknown-kind (get-in item-frame [:presentation-error :reason])))
    (is (= (trace/canonical-edn {:answer 42}) (:source-edn item-frame)))
    (is (= :kind/not-installed (get-in item-frame [:selection :kind])))))

(deftest datafy-is-cheap-and-navigation-is-revision-scoped
  (let [s (session/start)
        _ (session/append-item! s (item "x" 0 {:answer 42}))
        summary (datafy/datafy s)
        token (:items summary)]
    (is (= 1 (:item-count summary)))
    (is (nil? (:presentation summary)))
    (is (= 1 (count (datafy/nav s :items token))))
    (session/append-item! s (item "x" 1 {:answer 43}))
    (is (= :stale-navigation
           (:reason (caught-data #(protocols/nav s :items token)))))
    (is (= :unchanged (protocols/nav s :other :unchanged)))))

(deftest visible-item-bound-is-explicit-and-keeps-most-recent-current-items
  (let [s (session/start {:visible-items 2})]
    (doseq [id ["c" "a" "b"]]
      (session/append-item! s (item id 0 {:id id})))
    (let [frame (session/frame s)]
      (is (= 3 (:item-count frame)))
      (is (= 3 (:current-item-count frame)))
      (is (= 1 (:omitted-item-count frame)))
      (is (= ["a" "b"]
             (mapv #(get-in % [:coordinate :item-id]) (:items frame)))))))

(deftest presentation-never-holds-the-mutation-lock
  (let [entered (promise)
        release (promise)
        s (session/start
           {:kind-registry
            {:kind/blocked
             {:present (fn [value]
                         (deliver entered true)
                         @release
                         {:summary "Released"
                          :fields [{:label "Value" :value value}]})}}})]
    (session/append-item!
     s (assoc (item "blocked" 0 {:answer 42})
              :suggested-kind :kind/blocked))
    (let [rendering (future (session/frame s))]
      (is (= true (deref entered 1000 ::timeout)))
      ;; Rendering is advisory. A trusted presenter that blocks must not hold
      ;; the session's mutation lock or delay an unrelated definite append.
      (is (= 2
             (:revision
              (deref (future (session/append-item!
                              s (item "other" 0 {:answer 43})))
                     1000 ::timeout))))
      (deliver release true)
      (is (= "Released"
             (get-in (deref rendering 1000 ::timeout)
                     [:items 0 :presentation :summary]))))))

(deftest invalid-config-and-stale-mutators-fail-closed
  (is (= :unknown-config-keys
         (:reason (caught-data #(session/start {:extra true})))))
  (is (= :visible-items-out-of-range
         (:reason (caught-data #(session/start {:visible-items 0})))))
  (let [s (session/start)
        _ (session/append-item! s (item "x" 0 {:answer 42}))
        stored (workbench/item (session/document s) "x" 0)
        stale (update-in (:source-fingerprint stored) [:crc32c] bit-xor 1)]
    (is (= :stale-source
           (:reason
            (caught-data
             #(session/set-item-kind!
               s {:item-id "x" :source-revision 0
                  :source-fingerprint stale :kind :kind/x
                  :provenance (provenance {:user :alice})})))))
    (is (= 1 (:revision (session/snapshot s))))))
