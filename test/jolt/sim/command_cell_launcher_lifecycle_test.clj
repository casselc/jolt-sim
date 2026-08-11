(ns jolt.sim.command-cell-launcher-lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.command-cell-session :as command-cell-session]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.workbench-session :as workbench-session]
            [maelstrom-broadcast-workbench.flow-retained :as broadcast-flow]
            [maelstrom-broadcast-workbench.main :as broadcast]
            [outbox-workbench.flow-retained :as outbox-flow]
            [outbox-workbench.flow-ripple-main :as outbox]))

(defn- dead-worker-snapshot []
  {:child {:alive? false} :next-sequence 0 :status :ready})

(defn- index-of [values target]
  (first (keep-indexed #(when (= target %2) %1) values)))

(defn- caught-error [thunk]
  (try (thunk) nil (catch :default error error)))

(deftest broadcast-launcher-attaches-one-shared-borrowed-command-session
  (let [calls (atom [])
        captured (atom nil)]
    (with-redefs [retained/start! (fn [_] :broadcast-worker)
                  retained/snapshot
                  (fn [_]
                    (swap! calls conj :worker-stop-observe)
                    (dead-worker-snapshot))
                  eval-session/start (fn [] :eval-session)
                  workbench-session/start (fn [& _] :shared-items)
                  effect-session/retained-worker-service
                  (fn [worker]
                    (swap! calls conj [:borrow worker])
                    :borrowed-service)
                  broadcast-flow/start-command-cell-session
                  (fn [config]
                    (reset! captured config)
                    :command-cells)
                  viewer/start-workbench!
                  (fn [_ capabilities]
                    (swap! calls conj [:viewer capabilities])
                    :server)
                  viewer/stop! (fn [_] (swap! calls conj :viewer-stop))
                  command-cell-session/close!
                  (fn [_] (swap! calls conj :command-cell-close))]
      (let [workbench
            (broadcast/start! {:viewer-config {}
                               :retained-config {}})]
        (is (= :borrowed-service (:worker @captured)))
        (is (= :shared-items (:workbench @captured)))
        (is (= :command-cells (:command-cell-session workbench)))
        (is (= :command-cells
               (get-in @calls [1 1 :command-cell-session])))
        (broadcast/shutdown! workbench)
        (is (< (index-of @calls :viewer-stop)
               (index-of @calls :command-cell-close)
               (index-of @calls :worker-stop-observe)))))))

(deftest outbox-launcher-shares-worker-service-and-closes-admission-first
  (let [calls (atom [])
        captured (atom nil)]
    (with-redefs [retained/start! (fn [_] :outbox-worker)
                  retained/snapshot
                  (fn [_]
                    (swap! calls conj :worker-stop-observe)
                    (dead-worker-snapshot))
                  eval-session/start (fn [] :eval-session)
                  workbench-session/start (fn [& _] :shared-items)
                  effect-session/retained-worker-service
                  (fn [worker]
                    (swap! calls conj [:borrow worker])
                    :borrowed-service)
                  effect-session/attach!
                  (fn [config]
                    (swap! calls conj [:bridge (:worker config)])
                    :fixed-bridge)
                  effect-session/close!
                  (fn [_] (swap! calls conj :bridge-close))
                  outbox-flow/start-command-cell-session
                  (fn [config]
                    (reset! captured config)
                    :command-cells)
                  viewer/start-workbench!
                  (fn [_ capabilities]
                    (swap! calls conj [:viewer capabilities])
                    :server)
                  viewer/stop! (fn [_] (swap! calls conj :viewer-stop))
                  command-cell-session/close!
                  (fn [_] (swap! calls conj :command-cell-close))]
      (let [workbench
            (outbox/start! {:viewer-config {}
                            :retained-config {}
                            :submit-command
                            {:op :submit
                             :command {:request-id "request-1"
                                       :entity-id "entity-1"
                                       :payload [0 255]}}})]
        (is (= :borrowed-service (:worker @captured)))
        (is (= :shared-items (:workbench @captured)))
        (is (= [[:borrow :outbox-worker]
                [:bridge :borrowed-service]]
               (take 2 @calls)))
        (is (= :fixed-bridge (:bridge workbench)))
        (is (= :command-cells (:command-cell-session workbench)))
        (outbox/shutdown! workbench)
        (is (< (index-of @calls :viewer-stop)
               (index-of @calls :command-cell-close)
               (index-of @calls :bridge-close)
               (index-of @calls :worker-stop-observe)))))))

(deftest launcher-startup-failures-retain-owner-evidence-and-close-admission
  (testing "Broadcast"
    (let [calls (atom [])
          snapshots (atom 0)]
      (with-redefs [retained/start! (fn [_] :worker)
                    retained/snapshot
                    (fn [_]
                      (let [n (swap! snapshots inc)]
                        (swap! calls conj [:worker-observe n])
                        (dead-worker-snapshot)))
                    eval-session/start (fn [] :eval)
                    eval-session/close! (fn [_] nil)
                    workbench-session/start (fn [& _] :items)
                    effect-session/retained-worker-service (fn [_] :borrowed)
                    broadcast-flow/start-command-cell-session
                    (fn [_] :command-cells)
                    command-cell-session/close!
                    (fn [_] (swap! calls conj :command-cell-close))
                    viewer/start-workbench!
                    (fn [& _] (throw (ex-info "viewer failed" {:stage :viewer})))]
        (let [error
              (caught-error
               #(broadcast/start! {:viewer-config {} :retained-config {}}))
              data (ex-data error)]
          (is (map? (:maelstrom-broadcast-workbench.main/worker-before-cleanup
                     data)))
          (is (= :viewer (:stage data)))
          (is (< (index-of @calls :command-cell-close)
                 (index-of @calls [:worker-observe 2])))))))

  (testing "Outbox"
    (let [calls (atom [])
          snapshots (atom 0)]
      (with-redefs [retained/start! (fn [_] :worker)
                    retained/snapshot
                    (fn [_]
                      (let [n (swap! snapshots inc)]
                        (swap! calls conj [:worker-observe n])
                        (dead-worker-snapshot)))
                    eval-session/start (fn [] :eval)
                    eval-session/close! (fn [_] nil)
                    workbench-session/start (fn [& _] :items)
                    effect-session/retained-worker-service (fn [_] :borrowed)
                    effect-session/attach! (fn [_] :bridge)
                    effect-session/close! (fn [_] (swap! calls conj :bridge-close))
                    outbox-flow/start-command-cell-session
                    (fn [_] :command-cells)
                    command-cell-session/close!
                    (fn [_] (swap! calls conj :command-cell-close))
                    viewer/start-workbench!
                    (fn [& _] (throw (ex-info "viewer failed" {:stage :viewer})))]
        (let [error
              (caught-error
               #(outbox/start!
                 {:viewer-config {} :retained-config {}
                  :submit-command
                  {:op :submit
                   :command {:request-id "request-1" :entity-id "entity-1"
                             :payload [0 255]}}}))
              data (ex-data error)]
          (is (map? (:outbox-workbench.flow-ripple-main/worker-before-cleanup
                     data)))
          (is (= :viewer-start
                 (:outbox-workbench.flow-ripple-main/startup-phase data)))
          (is (= :viewer (:stage data)))
          (is (< (index-of @calls :command-cell-close)
                 (index-of @calls [:worker-observe 2]))))))))
