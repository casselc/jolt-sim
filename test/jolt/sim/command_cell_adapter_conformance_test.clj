(ns jolt.sim.command-cell-adapter-conformance-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.command-cell-session :as command-session]
            [jolt.sim.trace :as trace]
            [jolt.sim.workbench :as workbench]
            [jolt.sim.workbench-session :as workbench-session]
            [maelstrom-broadcast-workbench.flow-retained :as broadcast]
            [outbox-workbench.flow-retained :as outbox]))

(def broadcast-value
  {:kind :jolt.sim/maelstrom-broadcast-live
   :status :running
   :regime-revision 0
   :connections {}
   :ready-mailboxes []
   :mailboxes {}
   :nodes {}
   :drops {:dropped-total 0}
   :client-replies {:count 0 :tail []}})

(def outbox-empty-value
  {:operation :deliver
   :result {:status :empty}
   :snapshot {:store-state {:outbox []}
              :receiver-requests {:count 0}}})

(def adapter-cases
  [{:name :broadcast
    :start broadcast/start-command-cell-session
    :catalog-count 8
    :catalog-ids
    #{:example.broadcast/inspect
      :example.broadcast/bootstrap
      :example.broadcast/step
      :example.broadcast/set-connection-regime
      :example.broadcast/heal
      :example.broadcast/retry
      :example.broadcast/read
      :example.broadcast/stop}
    :inputs
    {:example.broadcast/inspect {:op :inspect}
     :example.broadcast/bootstrap {:op :bootstrap}
     :example.broadcast/step {:op :step :node-id "n1"}
     :example.broadcast/set-connection-regime
     {:op :set-connection-regime
      :connection ["n1" "n2"]
      :expected-revision 0
      :regime :normal}
     :example.broadcast/heal {:op :heal}
     :example.broadcast/retry {:op :retry}
     :example.broadcast/read {:op :read}
     :example.broadcast/stop {:op :stop}}
    :cell-id :example.broadcast/inspect
    :input {:op :inspect}
    :invalid-input {:op :inspect :extra true}
    :completed-value broadcast-value
    :expected-projection
    {:operation :inspect
     :status :running
     :regime-revision 0
     :connections []
     :ready-mailboxes []
     :mailbox-counts {}
     :messages {}
     :pending-counts {}
     :dropped-total 0
     :client-reply-count 0
     :read-messages []}}
   {:name :outbox
    :start outbox/start-command-cell-session
    :catalog-count 2
    :catalog-ids #{:example.outbox/submit :example.outbox/deliver}
    :inputs
    {:example.outbox/submit
     {:op :submit
      :command {:request-id "req-1"
                :entity-id "entity-a"
                :payload [0 127 128 255]}}
     :example.outbox/deliver {:op :deliver}}
    ;; Deliver is deliberately independent: before any submit it projects a
    ;; valid, explicit :empty application state.
    :cell-id :example.outbox/deliver
    :input {:op :deliver}
    :invalid-input {:op :deliver :command {:request-id "req"
                                            :entity-id "entity"
                                            :payload [0]}}
    :completed-value outbox-empty-value
    :expected-projection
    {:operation :deliver
     :row-status :empty
     :receiver-requests 0}}])

(defn- completed [sequence value]
  {:receipt {:status :completed :sequence sequence :value value}})

(defn- failed [sequence reason]
  {:receipt {:status :failed :sequence sequence :error {:reason reason}}})

(defn- scripted-worker
  ([commands] (scripted-worker commands []))
  ([commands reconciliations]
   (let [commands (atom (vec commands))
         reconciliations (atom (vec reconciliations))
         state (atom {:status :ready
                      :next-sequence 0
                      :uncertain-sequence nil
                      :command-calls 0
                      :reconcile-calls 0
                      :snapshot-calls 0
                      :published []
                      :alive? true})
         apply-outcome!
         (fn [outcome]
           (cond
             (:receipt outcome)
             (let [receipt (:receipt outcome)]
               (swap! state assoc
                      :status :ready
                      :next-sequence (inc (:sequence receipt))
                      :uncertain-sequence nil)
               receipt)

             (:uncertain outcome)
             (let [sequence (:next-sequence @state)]
               (swap! state assoc :status :uncertain
                      :uncertain-sequence sequence)
               (throw (ex-info "uncertain publication"
                               {:reason (:uncertain outcome)
                                :sequence sequence
                                :uncertain-sequence sequence
                                :published? true})))

             :else
             (do
               (swap! state assoc :status :failed
                      :uncertain-sequence nil)
               (throw (ex-info "failed publication"
                               {:reason (:failure outcome)})))))]
     {:state state
      :service
      {:command!
       (fn [command]
         (swap! state #(-> %
                           (update :command-calls inc)
                           (update :published conj command)))
         (let [outcome (first @commands)]
           (swap! commands #(vec (rest %)))
           (apply-outcome! outcome)))
       :reconcile!
       (fn []
         (swap! state update :reconcile-calls inc)
         (let [outcome (first @reconciliations)]
           (swap! reconciliations #(vec (rest %)))
           (apply-outcome! outcome)))
       :reconcile-sequence!
       (fn [_]
         (swap! state update :reconcile-calls inc)
         (let [outcome (first @reconciliations)]
           (swap! reconciliations #(vec (rest %)))
           (apply-outcome! outcome)))
       :snapshot
       (fn []
         (swap! state update :snapshot-calls inc)
         (select-keys @state
                      [:status :next-sequence :uncertain-sequence]))}})))

(defn- caught-data [thunk]
  (try (thunk) nil (catch :default error (ex-data error))))

(defn- start-case [adapter worker items stream]
  ((:start adapter)
   {:worker (:service worker)
    :workbench items
    :evidence-stream-id stream}))

(defn- function-free? [value]
  (not-any? fn? (tree-seq coll? seq value)))

(defn- item-count [document item-id source-revision]
  (count
   (filter
    (fn [entry]
      (and (= :item/append (:op entry))
           (= item-id (get-in entry [:item :id]))
           (= source-revision
              (get-in entry [:item :source-revision]))))
    (:jolt.sim.workbench/journal document))))

(deftest every-advertised-cell-compiles-and-previews-without-worker-calls
  (doseq [{:keys [name catalog-ids inputs] :as adapter} adapter-cases
          cell-id (sort-by pr-str catalog-ids)]
    (testing (str name " " cell-id)
      (let [worker (scripted-worker [])
            session
            (start-case adapter worker (workbench-session/start)
                        (str (clojure.core/name name) "-"
                             (clojure.core/name cell-id)))]
        (is (= :prepared
               (:status
                (command-session/prepare!
                 session {:revision 0
                          :cell-id cell-id
                          :input (get inputs cell-id)}))))
        (is (= 1 (count (command-session/branches session))))
        (is (= [0 0 0]
               ((juxt :command-calls :reconcile-calls :snapshot-calls)
                @(:state worker))))))))

(deftest adapters-share-the-exact-command-cell-contract
  (doseq [{:keys [name catalog-count catalog-ids cell-id input invalid-input
                  completed-value expected-projection]
           :as adapter}
          adapter-cases]
    (testing (str name)
      (let [worker (scripted-worker [(completed 0 completed-value)])
            items (workbench-session/start)
            stream (str (clojure.core/name name) "-definite")
            session (start-case adapter worker items stream)
            catalog (command-session/catalog session)]
        (is (= catalog-count (count catalog)))
        (is (= catalog-ids (set (map :id catalog))))
        (is (every? function-free? catalog))

        (testing "selected exact schema rejects before compiler or worker"
          (is (= :jolt.sim.schema/invalid-value
                 (:type
                  (caught-data
                   #(command-session/prepare!
                     session {:revision 0
                              :cell-id cell-id
                              :input invalid-input})))))
          (is (= 0 (:revision (command-session/snapshot session))))
          (is (= [0 0 0]
                 ((juxt :command-calls :reconcile-calls :snapshot-calls)
                  @(:state worker)))))

        (let [prepared
              (command-session/prepare!
               session {:revision 0 :cell-id cell-id :input input})
              previews-1 (command-session/branches session)
              previews-2 (command-session/branches session)
              coordinate (get-in previews-1 [0 :coordinate])]
          (is (= :prepared (:status prepared)))
          (is (= previews-1 previews-2))
          (is (= 1 (count previews-1)))
          (is (= [0 0 0]
                 ((juxt :command-calls :reconcile-calls :snapshot-calls)
                  @(:state worker))))

          (testing "a wrong inner action and wrong outer revision publish nothing"
            (let [wrong-inner
                  {:revision (:revision coordinate)
                   :branch {:revision (get-in coordinate [:branch :revision])
                            :action [:not-enabled 0]}}]
              (is (= :jolt.sim.kernel/invalid-machine-action
                     (:type (caught-data
                             #(command-session/step! session wrong-inner)))))
              (is (= 0 (:command-calls @(:state worker)))))
            (is (= :stale-revision
                   (:reason
                    (caught-data
                     #(command-session/step!
                       session (update coordinate :revision inc))))))
            (is (= 0 (:command-calls @(:state worker)))))

          (let [result (command-session/step! session coordinate)
                document (workbench-session/document items)
                projected-id
                (str "command-cell/" stream "/projected-receipt")]
            (is (true? (:committed? result)))
            (is (= expected-projection
                   (get-in result [:projection :value])))
            (is (= [input] (:published @(:state worker))))
            (is (= 1 (:command-calls @(:state worker))))
            (is (= 1 (item-count document projected-id 2)))
            (is (= expected-projection
                   (trace/restore-value
                    (:value (workbench/item document projected-id 2)))))
            (is (= :stale-revision
                   (:reason (caught-data
                             #(command-session/step! session coordinate)))))
            (is (= 1 (:command-calls @(:state worker))))

            (let [snapshot-count (:snapshot-calls @(:state worker))]
              (command-session/close! session)
              (is (true? (:alive? @(:state worker))))
              (is (= 1 (:command-calls @(:state worker))))
              (is (= 0 (:reconcile-calls @(:state worker))))
              (is (= (inc snapshot-count)
                     (:snapshot-calls @(:state worker)))))))))))

(deftest failed-receipts-skip-every-application-projector
  (doseq [{:keys [name cell-id input] :as adapter} adapter-cases]
    (testing (str name)
      (let [worker (scripted-worker [(failed 0 :rejected)])
            items (workbench-session/start)
            stream (str (clojure.core/name name) "-failed")
            session (start-case adapter worker items stream)
            _ (command-session/prepare!
               session {:revision 0 :cell-id cell-id :input input})
            result
            (command-session/step!
             session (get-in (command-session/branches session)
                             [0 :coordinate]))
            document (workbench-session/document items)]
        (is (true? (:committed? result)))
        (is (nil? (:projection result)))
        (is (= :failed
               (get-in result [:delivery :effects :records 0 :status])))
        (is (nil? (workbench/item
                   document
                   (str "command-cell/" stream "/projected-receipt") 2)))
        (is (= 1 (:command-calls @(:state worker))))))))

(deftest uncertain-publications-reconcile-without-republication
  (doseq [{:keys [name cell-id input completed-value expected-projection]
           :as adapter}
          adapter-cases]
    (testing (str name)
      (let [worker (scripted-worker [{:uncertain :deadline}]
                                    [(completed 0 completed-value)])
            items (workbench-session/start)
            stream (str (clojure.core/name name) "-uncertain")
            session (start-case adapter worker items stream)
            _ (command-session/prepare!
               session {:revision 0 :cell-id cell-id :input input})
            coordinate (get-in (command-session/branches session)
                               [0 :coordinate])
            result (command-session/step! session coordinate)]
        (is (= :uncertain (:status result)))
        (is (= [input] (:published @(:state worker))))
        (is (= :uncertain
               (:reason
                (caught-data
                 #(command-session/prepare!
                   session {:revision 2 :cell-id cell-id :input input})))))
        (let [settled (command-session/reconcile! session)
              document (workbench-session/document items)
              projected-id
              (str "command-cell/" stream "/projected-receipt")]
          (is (= :ready (:status settled)))
          (is (= expected-projection
                 (get-in settled [:projection :value])))
          (is (= 1 (:command-calls @(:state worker))))
          (is (= 1 (:reconcile-calls @(:state worker))))
          (is (= [input] (:published @(:state worker))))
          (is (= 1 (item-count document projected-id 3))))))))

(deftest outbox-submit-boundary-requires-nonempty-ids-and-octets
  (let [valid {:op :submit
               :command {:request-id "req-1"
                         :entity-id "entity-a"
                         :payload [0 127 128 255]}}
        invalids [(assoc-in valid [:command :request-id] "")
                  (assoc-in valid [:command :entity-id] "")
                  (assoc-in valid [:command :payload] [-1])
                  (assoc-in valid [:command :payload] [256])]]
    (doseq [input invalids]
      (let [worker (scripted-worker [])
            session (outbox/start-command-cell-session
                     {:worker (:service worker)
                      :workbench (workbench-session/start)
                      :evidence-stream-id "outbox-invalid"})]
        (is (= :jolt.sim.schema/invalid-value
               (:type
                (caught-data
                 #(command-session/prepare!
                   session {:revision 0
                            :cell-id :example.outbox/submit
                            :input input})))))
        (is (= [0 0 0]
               ((juxt :command-calls :reconcile-calls :snapshot-calls)
                @(:state worker))))))))
