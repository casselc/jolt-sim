(ns jolt.sim.activity-view-test
  (:require [clojure.core.protocols :as protocols]
            [clojure.datafy :as datafy]
            [clojure.test :as test :refer [deftest is]]
            [jolt.fs :as fs]
            [jolt.sim.activity :as activity]
            [jolt.sim.activity-view :as activity-view]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.process-explorer :as process-explorer]))

(defmacro with-retained-dir [[binding] & body]
  `(let [~binding (str (fs/create-temp-dir
                        {:prefix "jolt-sim-activity-view-test-"}))
         failures-before# (+ (:fail @test/counters) (:error @test/counters))
         completed?# (volatile! false)]
     (try
       ~@body
       (vreset! completed?# true)
       (finally
         (if (and @completed?#
                  (= failures-before#
                     (+ (:fail @test/counters) (:error @test/counters))))
           (when (fs/exists? ~binding) (fs/delete-tree ~binding))
           (println "Retained unexpected activity-view artifacts at"
                    ~binding))))))

(defn- page [cursor remaining?]
  (let [accepted-count (if remaining? (+ cursor 2) (inc cursor))]
    {:version 1
     :cursor cursor
     :next-cursor (inc cursor)
     :accepted-count accepted-count
     :remaining? remaining?
     :events [{:sequence cursor
               :event [:jolt.sim.explore/scenario-started
                       nil nil {:scenario 'example/checkout}]}]
     :recovery {:status :complete
                :reason nil
                :sequence accepted-count
                :last-good-offset 64
                :raw-tail-bytes 0
                :image-truncated? false
                :class nil}
     :observer-status nil}))

(deftest present-page-is-a-ui-neutral-immutable-value
  (let [view (activity-view/present-page
              (page 0 true)
              presentation/default-activity-registry)]
    (is (= activity-view/page-type
           (:jolt.sim.activity-view/type view)))
    (is (= activity-view/page-kind (:kind view)))
    (is (= {:cursor 1} (:next-page view)))
    (is (= :jolt.sim.kind/scenario-started
           (get-in view [:events 0 :kind])))
    (is (= "jolt.sim.explore/scenario-started"
           (get-in view [:events 0 :tag])))
    (is (= "[:jolt.sim.explore/scenario-started nil nil {:scenario example/checkout}]"
           (get-in view [:events 0 :edn])))
    (is (= "example/checkout"
           (get-in view [:events 0 :fields 0 :value-edn])))))

(deftest datafy-and-nav-use-the-same-public-page-model
  (let [reads (atom [])
        retained {:artifact-dir "/trusted/run" :activity {}}
        source
        (with-redefs [process-explorer/read-activity-page
                      (fn [outcome cursor]
                        (swap! reads conj [outcome cursor])
                        (page cursor (zero? cursor)))]
          (let [source (activity-view/source
                        retained presentation/default-activity-registry)
                first-page (datafy/datafy source)
                original (:clojure.datafy/obj (meta first-page))
                second-page (datafy/nav original
                                        :next-page
                                        (:next-page first-page))]
            (is (= [0 1] (mapv (comp second) @reads)))
            (is (= (activity-view/present-page
                    (page 0 true)
                    presentation/default-activity-registry)
                   first-page))
            (is (= 1 (:cursor second-page)))
            (is (nil? (:next-page second-page)))
            (is (identical? source original))))]))

(deftest activity-navigation-fails-closed
  (let [source (activity-view/source
                {:artifact-dir "/trusted/run" :activity {}}
                presentation/default-activity-registry)]
    (doseq [value [1 {:cursor -1} {:cursor 1 :extra true}]]
      (let [error (try
                    (protocols/nav source :next-page value)
                    nil
                    (catch :default error error))]
        (is (= :jolt.sim.activity-view/invalid-navigation
               (:type (ex-data error))))))))

(deftest public-page-projection-rejects-invalid-or-future-pages
  (let [valid (page 0 true)
        cases [[(assoc valid :version 2) :unsupported-version]
               [(assoc valid :next-cursor 2) :invalid-continuation]
               [(assoc valid :remaining? false) :invalid-continuation]
               [(assoc-in valid [:events 0 :sequence] 1) :invalid-events]
               [(assoc valid :extra true) :invalid-page-shape]
               [(assoc-in valid [:recovery :status] :future)
                :invalid-recovery-status]
               [(assoc-in valid [:recovery :sequence] 1)
                :invalid-recovery-sequence]
               [(assoc valid :observer-status {:health :healthy})
                :invalid-observer-status]
               [(assoc valid :observer-status false)
                :invalid-observer-status]
               [(assoc valid :observer-status
                       {:health :failed :failure {}
                        :sequence 0 :accepted 0 :capped? false
                        :durability :process-crash :closed? true})
                :invalid-observer-status]
               [(assoc valid :observer-status
                       {:health :failed
                        :failure {:phase :append :reason :failed
                                  :unknown true}
                        :sequence 0 :accepted 0 :capped? false
                        :durability :process-crash :closed? true})
                :invalid-observer-status]
               [(assoc valid :observer-status
                       {:health :failed
                        :failure {:phase :append :reason :failed
                                  :count -1}
                        :sequence 0 :accepted 0 :capped? false
                        :durability :process-crash :closed? true})
                :invalid-observer-status]]]
    (doseq [[candidate reason] cases]
      (let [error (try
                    (activity-view/present-page
                     candidate presentation/default-activity-registry)
                    nil
                    (catch :default error error))]
        (is (= :jolt.sim.activity-view/invalid-page
               (:type (ex-data error))))
        (is (= reason (:reason (ex-data error))))))))

(deftest retained-pages-are-reusable-through-datafy-nav-and-tap
  (with-retained-dir [run-dir]
    (let [path (str (fs/path run-dir "activity.journal"))
          run-id (byte-array (range 16))
          observer (activity/open-observer! {:path path :run-id run-id})
          _ (activity/call-with-observer
             observer
             (fn []
               (dotimes [index 40]
                 (activity/emit!
                  [::checkpoint nil nil {:index index}]))))
          closed (activity/close-observer! observer)
          registry
          (presentation/activity-registry
           presentation/default-activity-registry
           {::checkpoint
            {:kind :jolt.sim.kind/test-checkpoint
             :present
             (fn [event]
               {:summary (str "Checkpoint " (:index (nth event 3)))
                :fields []})}})
          source (activity-view/source
                  {:status :completed
                   :artifact-dir run-dir
                   :activity {:observer-status closed}}
                  registry)
          first-page (datafy/datafy source)
          original (:clojure.datafy/obj (meta first-page))
          second-page (datafy/nav original
                                  :next-page
                                  (:next-page first-page))
          tapped (promise)
          receiver #(deliver tapped %)]
      (add-tap receiver)
      (try
        (tap> first-page)
        (is (= first-page (deref tapped 1000 ::tap-timeout)))
        (finally
          (remove-tap receiver)))
      (is (= 32 (count (:events first-page))))
      (is (= 8 (count (:events second-page))))
      (is (= (vec (range 40))
             (mapv :sequence
                   (concat (:events first-page) (:events second-page)))))
      (is (every? #(= :jolt.sim.kind/test-checkpoint (:kind %))
                  (concat (:events first-page) (:events second-page))))
      (is (= {:cursor 32} (:next-page first-page)))
      (is (nil? (:next-page second-page))))))
