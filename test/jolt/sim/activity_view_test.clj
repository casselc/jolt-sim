(ns jolt.sim.activity-view-test
  (:require [clojure.core.protocols :as protocols]
            [clojure.datafy :as datafy]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.activity-view :as activity-view]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.process-explorer :as process-explorer]))

(defn- page [cursor remaining?]
  {:version 1
   :cursor cursor
   :next-cursor (inc cursor)
   :accepted-count (if remaining? (+ cursor 2) (inc cursor))
   :remaining? remaining?
   :events [{:sequence cursor
             :event [:jolt.sim.explore/scenario-started
                     nil nil {:scenario 'example/checkout}]}]
   :recovery {:status :complete
              :reason nil
              :sequence (inc cursor)
              :last-good-offset 64
              :raw-tail-bytes 0
              :image-truncated? false
              :class nil}
   :observer-status nil})

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
