(ns jolt.sim.session-view-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.session :as session]
            [jolt.sim.session-view :as session-view]))

(def ^:private read-frame-ops-var
  (resolve 'jolt.sim.session-view/read-frame*))

(def ^:private step-frame-ops-var
  (resolve 'jolt.sim.session-view/step-frame*))

(defn- caught-data [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- one-step-sim []
  {:tasks {0 (kernel/runnable :finish)}
   :world {:calls 0}
   :step (fn [{:keys [world]} _]
           (-> (kernel/step-complete :done)
               (kernel/with-world (update world :calls inc))))})

(deftest alternate-clients-can-read-and-step-without-ripple
  (let [session (session/start (one-step-sim))
        frame (session-view/read-frame session 0)
        branch (first (:branches frame))
        result (session-view/step-frame! session branch 0)]
    (is (= :frame (:jolt.sim.session-view/type frame)))
    (is (= :jolt.sim.kind/session-frame (:kind frame)))
    (is (= [{:revision 0 :action [:run 0]}] (:branches frame)))
    (is (= :committed (:status result)))
    (is (true? (:committed? result)))
    (is (= :jolt.sim.kind/session-step-result (:kind result)))
    (is (= {:branch {:revision 0 :action [:run 0]}
            :revision 1}
           (:ack result)))
      (is (= :completed (get-in result [:frame :status])))))

(deftest core-session-view-owns-bounded-journal-pagination
  (let [session (session/start
                 {:tasks {0 (kernel/runnable 0)}
                  :step (fn [_ state] (kernel/step-yield (inc state)))})]
    (dotimes [revision 300]
      (session/step! session {:revision revision :action [:run 0]}))
    (let [first-page (session-view/read-frame session 0)
          second-page (session-view/read-frame
                       session
                       (get-in first-page [:journal :next-cursor]))]
      (is (= 301 (get-in first-page [:journal :count])))
      (is (= session-view/max-journal-page-size
             (get-in first-page [:journal :page-size])))
      (is (= 256 (count (get-in first-page [:journal :entries]))))
      (is (true? (get-in first-page [:journal :remaining?])))
      (is (= 45 (get-in second-page [:journal :page-size])))
      (is (= 301 (get-in second-page [:journal :next-cursor])))
      (is (false? (get-in second-page [:journal :remaining?])))
      (is (= (vec (range 301))
             (mapv :seq
                   (concat (get-in first-page [:journal :entries])
                           (get-in second-page [:journal :entries]))))))))

(deftest core-session-view-rejects-cursors-before-command
  (let [session (session/start (one-step-sim))
        before (session/snapshot session)]
    (doseq [cursor [-1 "0" 1.5]]
      (is (= :jolt.sim.session-view/invalid-cursor
             (:type (caught-data
                     #(session-view/read-frame session cursor))))))
    (let [data (caught-data #(session-view/read-frame session 2))]
      (is (= :ahead-of-journal (:reason data)))
      (is (= 1 (:journal-count data))))
    (is (= :jolt.sim.session-view/invalid-cursor
           (:type (caught-data
                   #(session-view/step-frame!
                     session {:revision 0 :action [:run 0]} -1)))))
    (is (= before (session/snapshot session)))))

(deftest core-session-view-requires-stale-branch-reconfirmation
  (let [session (session/start
                 {:tasks {0 (kernel/runnable :a)
                          1 (kernel/runnable :b)}
                  :step (fn [_ state] (kernel/step-complete state))})]
    (session/step! session {:revision 0 :action [:run 1]})
    (let [stale (session-view/step-frame!
                 session {:revision 0 :action [:run 0]} 1)]
      (is (= :stale (:status stale)))
      (is (false? (:committed? stale)))
      (is (= 1 (get-in stale [:stale :actual-revision])))
      (is (= [{:revision 1 :action [:run 0]}]
             (get-in stale [:frame :branches])))
      (is (= 2 (count (session/journal session)))))))

(deftest core-session-view-preserves-commit-ack-on-refresh-failure
  (let [snapshot-calls (atom 0)
        step-calls (atom [])
        snapshot-value
        (fn []
          (let [call (swap! snapshot-calls inc)
                revision (cond (= call 1) 0 (odd? call) 2 :else 1)]
            {:revision revision
             :status nil
             :projection nil
             :branches [{:revision revision :action [:run 0]}]
             :journal {:count (inc revision)}}))
        ops {:snapshot snapshot-value
             :previews
             (fn []
               [{:branch {:revision 1 :action [:run 0]}
                 :site nil :status nil :projection nil :events []}])
             :journal (fn [] [{:seq 0 :command :start}
                              {:seq 1 :command :step}])
             :step! (fn [branch]
                      (swap! step-calls conj branch)
                      {:revision 1})}
        result (@step-frame-ops-var
                ops {:revision 0 :action [:run 0]} 0)]
    (is (= :committed (:status result)))
    (is (= {:branch {:revision 0 :action [:run 0]} :revision 1}
           (:ack result)))
    (is (nil? (:frame result)))
    (is (= :jolt.sim.session-view/coherence-failed
           (get-in result [:frame-error :type])))
    (is (= [{:revision 0 :action [:run 0]}] @step-calls))))

(deftest core-session-view-coherence-fails-closed
  (let [revision (atom 0)
        ops {:snapshot
             (fn []
               (let [value (swap! revision inc)]
                 {:revision value
                  :status nil
                  :projection nil
                  :branches []
                  :journal {:count 0}}))
             :previews (fn [] [])
             :journal (fn [] [])
             :step! (fn [_] (throw (ex-info "unused" {})))}
        data (caught-data #(@read-frame-ops-var ops 0))]
    (is (= :jolt.sim.session-view/coherence-failed (:type data)))
    (is (= 8 (:attempts data)))))
