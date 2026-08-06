(ns jolt.sim.presentation-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.trace :as trace]))

(def ^:private row-keys
  #{:index :tag :kind :kind-name :dispatch-key :dispatch-key-edn
    :step :time :task :edn :has-fields :summary :fields})

(defn- caught-data [f]
  (try (f) nil (catch :default error (ex-data error))))

(deftest activity-lifecycle-defaults-cover-the-worker-lifecycle
  (let [present (presentation/activity-event-presenter
                 presentation/default-activity-registry)
        scenario 'example.worker/replay-case
        events [[:jolt.sim.explore/scenario-started nil nil {:scenario scenario}]
                [:jolt.sim.explore/scenario-completed nil nil {:scenario scenario}]
                [:jolt.sim.explore/scenario-failed nil nil {:scenario scenario}]]
        rows (mapv (fn [index event] (present index event)) (range) events)]
    (is (= [:jolt.sim.kind/scenario-started
            :jolt.sim.kind/scenario-completed
            :jolt.sim.kind/scenario-failed]
           (mapv :kind rows)))
    (is (= ["jolt.sim.kind/scenario-started"
            "jolt.sim.kind/scenario-completed"
            "jolt.sim.kind/scenario-failed"]
           (mapv :kind-name rows)))
    (is (= ["Scenario example.worker/replay-case started"
            "Scenario example.worker/replay-case completed"
            "Scenario example.worker/replay-case failed"]
           (mapv :summary rows)))
    (doseq [[index row event] (map vector (range) rows events)]
      (is (= row-keys (set (keys row)))
          "activity rows carry exactly the existing closed row key set")
      (is (= index (:index row)))
      (is (nil? (:step row)))
      (is (nil? (:time row)))
      (is (nil? (:task row)))
      (is (= (nth event 0) (:dispatch-key row)))
      (is (= (trace/canonical-edn (nth event 0)) (:dispatch-key-edn row)))
      (is (= event (edn/read-string (:edn row)))
          "the complete canonical event EDN is retained")
      (is (= [{:label "Scenario"
               :value scenario
               :value-edn "example.worker/replay-case"}]
             (:fields row)))
      (is (true? (:has-fields row))))))

(deftest activity-task-transition-tag-dispatches-by-exact-tag-only
  (let [present (presentation/activity-event-presenter
                 presentation/default-activity-registry)
        event [:task/transition nil nil {:site :acme.clock/wait :op :sleep}]
        row (present 0 event)]
    (is (= :jolt.sim.kind/raw-event (:kind row))
        "an activity event tagged :task/transition must never receive the
         trace presenter's positional site/op dispatch")
    (is (= "Raw event task/transition" (:summary row)))
    (is (= :task/transition (:dispatch-key row)))
    (is (nil? (:step row)))
    (is (nil? (:time row)))
    (is (nil? (:task row)))
    (is (= event (edn/read-string (:edn row)))))
  (testing "an application override still wins by the exact event tag"
    (let [present (presentation/activity-event-presenter
                   (presentation/activity-registry
                    presentation/default-activity-registry
                    {:task/transition
                     {:kind :acme.kind/activity-transition
                      :present (fn [_]
                                 {:summary "activity tag wins" :fields []})}}))
          row (present 0 [:task/transition nil nil {}])]
      (is (= :acme.kind/activity-transition (:kind row)))
      (is (= "acme.kind/activity-transition" (:kind-name row)))
      (is (= "activity tag wins" (:summary row)))
      (is (= :task/transition (:dispatch-key row))))))

(deftest activity-unknown-tag-falls-back-to-raw-data
  (let [present (presentation/activity-event-presenter
                 presentation/default-activity-registry)
        event [:acme.activity/custom nil nil {:detail [1 2 3]}]
        row (present 7 event)]
    (is (= :jolt.sim.kind/raw-event (:kind row)))
    (is (= "jolt.sim.kind/raw-event" (:kind-name row)))
    (is (= "Raw event acme.activity/custom" (:summary row)))
    (is (= [] (:fields row)))
    (is (false? (:has-fields row)))
    (is (= 7 (:index row)))
    (is (nil? (:step row)))
    (is (nil? (:time row)))
    (is (nil? (:task row)))
    (is (= event (edn/read-string (:edn row))))))

(deftest activity-registry-overrides-win-by-exact-event-tag
  (let [entry (fn [label]
                {:kind (keyword "acme.kind" label)
                 :present (fn [_] {:summary label :fields []})})
        combined (presentation/activity-registry
                  nil
                  {:jolt.sim.explore/scenario-started (entry "default")}
                  {:jolt.sim.explore/scenario-started (entry "library")}
                  {:jolt.sim.explore/scenario-started (entry "application")})
        present (presentation/activity-event-presenter combined)
        row (present 0 [:jolt.sim.explore/scenario-started nil nil {}])]
    (is (= "application" (:summary row)))
    (is (= :jolt.sim.explore/scenario-started (:dispatch-key row)))))

(deftest activity-event-shape-is-exactly-four-slots-with-reserved-nils
  (let [present (presentation/activity-event-presenter
                 presentation/default-activity-registry)
        invalid-events [[:acme.activity/x nil nil]
                        [:acme.activity/x nil nil {} :extra]
                        [:unqualified nil nil {}]
                        [:acme.activity/x 0 nil {}]
                        [:acme.activity/x nil :reserved {}]
                        [:acme.activity/x nil nil []]
                        {:acme.activity/x {}}
                        "not-an-event"]]
    (doseq [event invalid-events]
      (let [data (caught-data #(present 0 event))]
        (is (= presentation/invalid-presentation (:type data))
            (str "event must be rejected: " (pr-str event)))
        (is (= :invalid-activity-event (:reason data)))))))

(deftest activity-registry-rejects-trace-dispatch-keys-and-bad-entries
  (let [valid-entry {:kind :acme.kind/x
                     :present (fn [_] {:summary "x" :fields []})}
        cases [[{[:task/transition :op :sleep] valid-entry}
                :invalid-activity-key]
               [{(presentation/site-key :acme.clock/wait) valid-entry}
                :invalid-activity-key]
               [{:unqualified valid-entry}
                :invalid-activity-key]
               [{:acme.activity/x {:kind :unqualified
                                   :present (fn [_] {:summary "x" :fields []})}}
                :invalid-kind]
               [{:acme.activity/x {:kind :acme.kind/x :present nil}}
                :invalid-presenter]
               [{:acme.activity/x {:kind :acme.kind/x}}
                :invalid-entry-shape]]]
    (doseq [[registry reason] cases]
      (let [data (caught-data
                  #(presentation/validate-activity-registry! registry))]
        (is (= presentation/invalid-registry (:type data))
            (str "registry must be rejected: " (pr-str registry)))
        (is (= reason (:reason data)))))))

(deftest activity-presenter-and-projection-failures-fail-closed
  (let [present (presentation/activity-event-presenter
                 {:acme.activity/x
                  {:kind :acme.kind/x
                   :present (fn [_] (throw (ex-info "boom" {})))}})
        data (caught-data #(present 0 [:acme.activity/x nil nil {}]))]
    (is (= presentation/invalid-presentation (:type data)))
    (is (= :presenter-threw (:reason data)))
    (is (= :acme.activity/x (:dispatch-key data))))
  (let [present (presentation/activity-event-presenter
                 {:acme.activity/x
                  {:kind :acme.kind/x
                   :present (fn [_] {:summary "x" :fields :wrong})}})
        data (caught-data #(present 0 [:acme.activity/x nil nil {}]))]
    (is (= presentation/invalid-presentation (:type data)))
    (is (= :invalid-fields (:reason data)))))

(deftest activity-event-presenter-validates-the-registry-once
  (let [calls (atom 0)
        original presentation/validate-activity-registry!]
    (with-redefs [presentation/validate-activity-registry!
                  (fn [registry]
                    (swap! calls inc)
                    (original registry))]
      (let [present (presentation/activity-event-presenter
                     presentation/default-activity-registry)
            event [:acme.activity/custom nil nil {}]]
        (present 0 event)
        (present 1 event)
        (is (= 1 @calls))))))
