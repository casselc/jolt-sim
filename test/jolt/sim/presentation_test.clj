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

(defn- restored-field [model label]
  (some->> (:fields model)
           (filter #(= label (:label %)))
           first
           :value
           trace/restore-value))

(def ^:private value-presenter-entry
  {:kind :acme.kind/widget
   :present
   (fn [value]
     {:summary (str "Widget " (:id value))
      :fields [{:label "Count" :value (:count value)}
               {:label "ID" :value (:id value)}]})})

(deftest value-registry-is-explicit-exact-and-later-wins
  (let [registry (presentation/value-registry
                  nil
                  {:acme/widget value-presenter-entry}
                  {:acme/widget
                   {:kind :acme.kind/override
                    :present (fn [_]
                               {:summary "override" :fields []})}})
        exact (presentation/present-value
               registry {:kind :acme/widget :id "w1" :count 2})
        unknown (presentation/present-value
                 registry {:kind :acme/other :id "w1"})
        nested (presentation/present-value
                registry {:payload {:kind :acme/widget}})]
    (is (= :acme.kind/override (:kind exact)))
    (is (= :acme/widget (:source-kind exact)))
    (is (= "override" (:summary exact)))
    (is (= :jolt.sim.kind/raw-value (:kind unknown)))
    (is (= :acme/other (:source-kind unknown)))
    (is (= :jolt.sim.kind/raw-value (:kind nested)))
    (is (nil? (:source-kind nested)))
    (is (= (trace/canonical-edn {:payload {:kind :acme/widget}})
           (:source-edn nested)))))

(deftest value-presenter-snapshots-source-and-output
  (let [bytes (byte-array [(byte 1) (byte 2)])
        seen (atom nil)
        registry
        {:acme/blob
         {:kind :acme.kind/blob
          :present (fn [value]
                     (reset! seen (:bytes value))
                     {:summary "blob"
                      :fields [{:label "Bytes" :value (:bytes value)}]})}}
        model (presentation/present-value
               registry {:kind :acme/blob :bytes bytes})]
    (aset-byte bytes 0 (byte 9))
    (is (not (identical? bytes @seen)))
    (is (= [1 2] (vec (restored-field model "Bytes"))))
    (is (= model (presentation/present-value
                  registry {:bytes (byte-array [(byte 1) (byte 2)])
                            :kind :acme/blob})))))

(deftest presenter-cannot-rewrite-the-captured-source-edn
  (let [bytes (byte-array [(byte 1) (byte 2)])
        registry
        {:acme/blob
         {:kind :acme.kind/blob
          :present (fn [value]
                     (aset-byte (:bytes value) 0 (byte 9))
                     {:summary "blob" :fields []})}}
        original {:kind :acme/blob :bytes bytes}
        expected (trace/canonical-edn original)
        model (presentation/present-value registry original)]
    (is (= expected (:source-edn model)))
    (is (= [1 2] (vec bytes)))))

(deftest value-topology-is-closed-bounded-and-deterministic
  (let [registry
        {:acme/network
         {:kind :acme.kind/topology
          :present
          (fn [_]
            {:summary "network"
             :fields [{:label "Nodes" :value 2}]
             :graph
             {:directed? false
              :nodes [{:id "a" :label "A" :status :acme.status/ready
                       :fields [{:label "Count" :value 1}]}
                      {:id "b" :label "B" :status nil :fields []}]
              :edges [{:id "a--b" :from "a" :to "b" :label "link"
                       :status :acme.status/partitioned :fields []}]}})}}
        model (presentation/present-value registry {:kind :acme/network})]
    (is (= :acme.kind/topology (:kind model)))
    (is (= ["a" "b"] (mapv :id (get-in model [:graph :nodes]))))
    (is (= ["a--b"] (mapv :id (get-in model [:graph :edges]))))
    (is (= 2 (restored-field model "Nodes")))
    (is (= model
           (presentation/present-value registry {:kind :acme/network})))))

(deftest value-registry-and-projections-fail-closed
  (doseq [[registry reason]
          [[{:plain value-presenter-entry} :invalid-source-kind]
           [{:acme/widget {:kind :plain :present identity}}
            :invalid-output-kind]
           [{:acme/widget {:kind :acme.kind/x}} :invalid-entry-shape]
           [{:acme/widget {:kind :acme.kind/x :present nil}}
            :invalid-presenter]]]
    (let [data (caught-data #(presentation/validate-value-registry! registry))]
      (is (= presentation/invalid-value-registry (:type data)))
      (is (= reason (:reason data)))))
  (let [bad (fn [projection]
              (caught-data
               #(presentation/present-value
                 {:acme/value {:kind :acme.kind/value
                               :present (fn [_] projection)}}
                 {:kind :acme/value})))]
    (is (= :invalid-projection-shape (:reason (bad {:summary "x"}))))
    (is (= :invalid-fields
           (:reason (bad {:summary "x" :fields (list)}))))
    (is (= :duplicate-field-label
           (:reason (bad {:summary "x"
                          :fields [{:label "A" :value 1}
                                   {:label "A" :value 2}]}))))
    (is (= :unstable-field-order
           (:reason (bad {:summary "x"
                          :fields [{:label "B" :value 1}
                                   {:label "A" :value 2}]}))))
    (is (= :presenter-threw
           (:reason
            (caught-data
             #(presentation/present-value
               {:acme/value
                {:kind :acme.kind/value
                 :present (fn [_] (throw (ex-info "boom" {})))}}
               {:kind :acme/value})))))))

(deftest malformed-topologies-fail-closed
  (let [data-for
        (fn [graph]
          (caught-data
           #(presentation/present-value
             {:acme/network
              {:kind :acme.kind/topology
               :present (fn [_]
                          {:summary "network" :fields [] :graph graph})}}
             {:kind :acme/network})))
        node (fn [id]
               {:id id :label id :status nil :fields []})
        edge (fn [id from to]
               {:id id :from from :to to :label id
                :status nil :fields []})]
    (is (= :duplicate-node-id
           (:reason (data-for
                     {:directed? false
                      :nodes [(node "a") (node "a")] :edges []}))))
    (is (= :dangling-edge
           (:reason (data-for
                     {:directed? false :nodes [(node "a")]
                      :edges [(edge "a--b" "a" "b")]}))))
    (is (= :duplicate-edge-id
           (:reason (data-for
                     {:directed? true :nodes [(node "a") (node "b")]
                      :edges [(edge "e" "a" "b")
                              (edge "e" "a" "b")]}))))
    (is (= :unstable-node-order
           (:reason (data-for
                     {:directed? false
                      :nodes [(node "b") (node "a")] :edges []}))))
    (is (= :unstable-undirected-edge
           (:reason (data-for
                     {:directed? false :nodes [(node "a") (node "b")]
                      :edges [(edge "b--a" "b" "a")]}))))
    (is (= :invalid-nodes
           (:reason (data-for
                     {:directed? false
                      :nodes (mapv #(node (str "n" %)) (range 257))
                      :edges []}))))
    (is (= :invalid-fields
           (:reason
            (caught-data
             #(presentation/present-value
               {:acme/value
                {:kind :acme.kind/value
                 :present
                 (fn [_]
                   {:summary "too many fields"
                    :fields
                    (mapv (fn [index]
                            {:label (str "Field " index) :value index})
                          (range 65))})}}
               {:kind :acme/value})))))))

(def ^:private topology-action-registry
  {:acme/network
   {:kind :acme.kind/topology
    :present
    (fn [_]
      {:summary "network"
       :fields []
       :graph
       {:directed? true
        :nodes [{:id "a" :label "A" :status nil :fields []
                 :actions [{:id "inspect"
                            :label "Inspect"
                            :command {:op :inspect :target "a"}
                            :enabled? true}
                           {:id "stop"
                            :label "Stop"
                            :command [:stop "a"]
                            :enabled? false}]}
                {:id "b" :label "B" :status nil :fields []}]
        :edges [{:id "a--b" :from "a" :to "b" :label "link"
                 :status nil :fields []
                 :actions [{:id "partition"
                            :label "Partition"
                            :command {:op :partition :link ["a" "b"]}
                            :enabled? true}]}]}})}})

(deftest topology-actions-are-closed-canonical-and-deterministic
  (let [model (presentation/present-value
               topology-action-registry {:kind :acme/network})
        actions (get-in model [:graph :nodes 0 :actions])]
    (is (= :acme.kind/topology (:kind model)))
    (is (= ["inspect" "stop"] (mapv :id actions)))
    (is (= ["Inspect" "Stop"] (mapv :label actions)))
    (is (= [true false] (mapv :enabled? actions)))
    (is (= [{:op :inspect :target "a"} [:stop "a"]]
           (mapv #(trace/restore-value (:command %)) actions))
        "commands are stored as inert canonical data that restores exactly")
    (is (= []
           (get-in model [:graph :nodes 1 :actions]))
        "an entity without an :actions key presents an explicitly empty vector")
    (is (= ["partition"]
           (mapv :id (get-in model [:graph :edges 0 :actions])))
        "edges carry the same closed action descriptor shape as nodes")
    (is (= model
           (presentation/present-value
            topology-action-registry {:kind :acme/network}))
        "action presentation is deterministic")))

(deftest topology-action-commands-are-snapshotted-like-field-values
  (let [bytes (byte-array [(byte 1) (byte 2)])
        registry
        {:acme/network
         {:kind :acme.kind/topology
          :present
          (fn [_]
            {:summary "network" :fields []
             :graph
             {:directed? false
              :nodes [{:id "a" :label "A" :status nil :fields []
                       :actions [{:id "send"
                                  :label "Send"
                                  :command {:payload bytes}
                                  :enabled? true}]}]
              :edges []}})}}
        model (presentation/present-value registry {:kind :acme/network})]
    (aset-byte bytes 0 (byte 9))
    (is (= [1 2]
           (vec (:payload
                 (trace/restore-value
                  (get-in model [:graph :nodes 0 :actions 0 :command])))))
        "the stored command is a snapshot, not a live mutable reference")))

(deftest malformed-topology-actions-fail-closed
  (let [data-for
        (fn [mutate]
          (caught-data
           #(presentation/present-value
             {:acme/network
              {:kind :acme.kind/topology
               :present
               (fn [_]
                 {:summary "network" :fields []
                  :graph
                  {:directed? false
                   :nodes [(mutate {:id "a" :label "A" :status nil
                                    :fields []})]
                   :edges []}})}}
             {:kind :acme/network})))
        action (fn []
                 {:id "go" :label "Go" :command {:op :go} :enabled? true})
        with-actions (fn [actions]
                       (fn [node] (assoc node :actions actions)))]
    (doseq [[mutate reason]
            [;; The descriptor key set is exactly
             ;; #{:id :label :command :enabled?}.
             [(with-actions [(assoc (action) :title "boom")])
              :invalid-action-shape]
             [(with-actions [(dissoc (action) :command)])
              :invalid-action-shape]
             [(with-actions [{:id "" :label "Go" :command {} :enabled? true}])
              :invalid-action-id]
             [(with-actions [{:id "go" :label 42 :command {} :enabled? true}])
              :invalid-action-label]
             [(with-actions [{:id "go" :label "" :command {} :enabled? true}])
              :invalid-action-label]
             [(with-actions [{:id "go" :label "Go" :command {} :enabled? "y"}])
              :invalid-action-enabled-flag]
             ;; A command must be canonicalizable data: functions, metadata,
             ;; and other opaque leaves are rejected, never deferred.
             [(with-actions [{:id "go" :label "Go"
                              :command {:f (fn [_] 1)} :enabled? true}])
              :unsupported-type]
             [(with-actions [(with-meta (action) {:x 1})])
              :invalid-action-shape]
             ;; The actions vector itself is bounded and metadata-free.
             [(with-actions (with-meta [(action)] {:x 1}))
              :invalid-actions]
             [(with-actions (list (action)))
              :invalid-actions]
             [(with-actions (mapv (fn [index]
                                    {:id (str "a" index) :label "Go"
                                     :command {} :enabled? true})
                                  (range 17)))
              :invalid-actions]
             ;; Action IDs are sorted and unique within one entity.
             [(with-actions [{:id "b" :label "B" :command {} :enabled? true}
                             {:id "a" :label "A" :command {} :enabled? true}])
              :unstable-action-order]
             [(with-actions [{:id "a" :label "A" :command {} :enabled? true}
                             {:id "a" :label "A2" :command {} :enabled? false}])
              :duplicate-action-id]
             ;; The command's canonical EDN is bounded.
             [(with-actions [{:id "go" :label "Go"
                              :command {:payload (apply str (repeat 5000 \x))}
                              :enabled? true}])
              :invalid-action-command]
             ;; The actual wire is the tagged canonical form. A collection of
             ;; compact ordinary entries can fit in ordinary EDN while its
             ;; unambiguous canonical encoding exceeds the wire bound.
             [(with-actions
               [{:id "go" :label "Go"
                 :command (into {}
                                (map (fn [index]
                                       [(keyword (str "k" index)) index])
                                     (range 80)))
                 :enabled? true}])
              :invalid-action-command]
             ;; A present-but-nil :actions is not the same as an omitted key.
             [(with-actions nil) :invalid-actions]]]
      (is (= reason (:reason (data-for mutate)))
          (str "mutation must fail closed with " reason))))
  (let [data (caught-data
              #(presentation/present-value
                {:acme/network
                 {:kind :acme.kind/topology
                  :present
                  (fn [_]
                    {:summary "network" :fields []
                     :graph
                     {:directed? false
                      :nodes [{:id "a" :label "A" :status nil :fields []}]
                      :edges [{:id "e" :from "a" :to "a" :label "e"
                               :status nil :fields [] :actions :bad}]}})}}
                {:kind :acme/network}))]
    (is (= :invalid-actions (:reason data))
        "edge action vectors are validated exactly like node action vectors")))
