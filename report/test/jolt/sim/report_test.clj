(ns jolt.sim.report-test
  "Focused contracts for the optional static-report dependency root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.maelstrom.official-run :as official-run]
            [jolt.sim.monitor :as monitor]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]
            [jolt.sim.trace-index :as trace-index]
            [selmer.util :as selmer-util]))

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(defn- countdown-config []
  {:tasks {0 (kernel/runnable 3)}
   :world {:order []}
   :step (fn [{:keys [task world]} remaining]
           (let [next-world (update world :order conj task)]
             (if (> remaining 1)
               (-> (kernel/step-yield (dec remaining))
                   (kernel/with-world next-world)
                   (kernel/at-site :countdown))
               (-> (kernel/step-complete :done)
                   (kernel/with-world next-world)
                   (kernel/at-site :finish)))))})

(defn- sample-events []
  (:trace (kernel/run (countdown-config))))

(defn- sample-doc []
  (trace/document (sample-events)))

(defn- official-run-doc
  ([] (official-run-doc 35))
  ([operation-count]
   (let [operations (mapv (fn [index]
                            {:type (if (even? index) :invoke :ok)
                             :f :broadcast :process (mod index 3) :time index
                             :value {:message index}})
                          (range operation-count))]
    (official-run/document
     {:profile :official :workload :broadcast :parameters {:nodes 3}}
     {:status :passed :exit 0 :official-valid? true :workload-valid? true
      :checks {:valid? true} :stats {:messages operation-count}}
     {:total-count operation-count :truncated? false :artifact "history.edn"
      :operations operations}
     [{:name "history.edn" :role :history :bytes 1234
       :sha256 (apply str (repeat 64 "0"))}]))))

(deftest official-run-report-pages-through-the-shared-view-model
  (let [doc (official-run-doc)
        vm (report/official-run->view-model doc)
        html (report/official-run->html doc)]
    (is (= 1 (:view-model-version vm)))
    (is (= 35 (count (:operations vm))))
    (is (= (range 35) (map :index (:operations vm))))
    (is (= html (report/official-run->html doc)))
    (is (string/includes? html "official Maelstrom run report"))
    (is (string/includes? html ":broadcast"))
    (is (= 35 (count (re-seq #"jolt.sim.kind/maelstrom-operation" html))))))

(deftest official-run-report-fails-closed-and-escapes-values
  (let [doc (official-run-doc)
        hostile (assoc-in doc
                          [:jolt.sim.maelstrom.official-run/run :parameters]
                          (trace/canonical-value {:label "<script>x</script>"}))
        html (report/official-run->html hostile)
        bad (dissoc doc :jolt.sim.maelstrom.official-run/outcome)]
    (is (string/includes? html "&lt;script&gt;x&lt;/script&gt;"))
    (is (not (string/includes? html "<script>x</script>")))
    (is (= official-run/invalid-document
           (:type (caught-data #(report/official-run->html bad)))))))

(deftest official-run-report-validates-once-at-the-history-limit
  (let [doc (official-run-doc official-run/max-captured-operations)
        original official-run/validate-document!
        calls (atom 0)]
    (with-redefs [official-run/validate-document!
                  (fn [value]
                    (swap! calls inc)
                    (original value))]
      (let [vm (report/official-run->view-model doc)]
        (is (= official-run/max-captured-operations
               (count (:operations vm))))
        (is (= 1 @calls))))))

(deftest official-run-report-preserves-namespaced-artifact-roles
  (let [artifact-key :jolt.sim.maelstrom.official-run/artifacts
        doc (update (official-run-doc) artifact-key conj
                    {:name "checker.edn" :role :checker/result :bytes 7
                     :sha256 (apply str (repeat 64 "1"))})
        vm (report/official-run->view-model doc)
        html (report/official-run->html doc)]
    (is (= ":checker/result" (get-in vm [:artifacts 1 :role-text])))
    (is (string/includes? html ":checker/result"))))

(defn- temp-path [suffix]
  (str (java.io.File/createTempFile "report" suffix)))

;; trace->view-model: shape, determinism, fail-closed validation

(deftest view-model-shape
  (let [doc (sample-doc)
        vm (report/trace->view-model doc)]
    (is (= 2 (:view-model-version vm)))
    (is (= trace/trace-version (:trace-version vm)))
    (is (= (count (:jolt.sim.trace/events doc)) (:event-count vm)))
    (is (= :run/completed (:terminal-tag vm)))
    (is (= "run/completed" (:terminal-label vm)))
    (is (map? (:tag-counts vm)))
    (is (= (count (:jolt.sim.trace/events doc)) (count (:events vm))))
    (is (false? (:has-monitors vm)))
    (is (= [] (:monitors vm)))
    (is (string? (:canonical-edn vm)))))

(deftest view-model-is-deterministic
  (let [doc (sample-doc)
        vm1 (report/trace->view-model doc)
        vm2 (report/trace->view-model doc)]
    (is (= vm1 vm2))
    ;; tag counts agree with the source of truth and are a sorted map
    (is (= (into (sorted-map)
                 (frequencies (map first (:jolt.sim.trace/events doc))))
           (:tag-counts vm1)))))

(deftest view-model-rejects-malformed-documents
  (let [events (sample-events)
        bad-doc {:jolt.sim.trace/version trace/trace-version
                 :jolt.sim.trace/events
                 (assoc events 0 (assoc (first events) 0 :unknown/event))}
        data (caught-data #(report/trace->view-model bad-doc))]
    (is (= trace/replay-diverged (:type data)))
    (is (= :malformed-trace (:reason data)))))

(deftest view-model-rejects-non-map-options
  (let [doc (sample-doc)
        data (caught-data #(report/trace->view-model doc :not-a-map))]
    (is (= report/invalid-options (:type data)))))

(deftest view-model-rejects-unknown-options
  (let [doc (sample-doc)
        data (caught-data
              #(report/trace->view-model doc {:monitors [] :surprise true}))]
    (is (= report/invalid-options (:type data)))
    (is (= :wrong-keys (:reason data)))))

(deftest outcome-requires-one-final-terminal-event
  (let [projection [:jolt.sim.value/map []]
        no-terminal (trace/document [(trace/initial-event projection)])
        ambiguous (trace/document
                   [(trace/initial-event projection)
                    (trace/completed-event 0 0 projection)
                    (trace/step-limit-event 1 0 projection)])]
    (doseq [doc [no-terminal ambiguous]]
      (let [vm (report/trace->view-model doc)]
        (is (nil? (:terminal-tag vm)))
        (is (= "unverified" (:terminal-label vm)))))))

;; event rows: index, tag, stable fields, lossless per-event EDN

(deftest event-rows-carry-stable-fields
  (let [doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/choose-event 0 0 [0] 0)
              (trace/transition-event 0 0 0 :yield
                                      [:jolt.sim.value/nil] [0] nil
                                      [:jolt.sim.value/map []])
              (trace/time-event 1 0 5 [0] [:jolt.sim.value/map []])
              (trace/completed-event 1 5 [:jolt.sim.value/map []])])
        rows (:events (report/trace->view-model doc))]
    (testing "run/initial has no step/time/task"
      (is (= "run/initial" (:tag (nth rows 0))))
      (is (nil? (:step (nth rows 0))))
      (is (nil? (:time (nth rows 0))))
      (is (nil? (:task (nth rows 0)))))
    (testing "schedule/choose carries step, time, chosen task"
      (is (= "schedule/choose" (:tag (nth rows 1))))
      (is (= 0 (:step (nth rows 1))))
      (is (= 0 (:time (nth rows 1))))
      (is (= 0 (:task (nth rows 1)))))
    (testing "task/transition carries step, time, task"
      (is (= "task/transition" (:tag (nth rows 2))))
      (is (= 0 (:step (nth rows 2))))
      (is (= 0 (:time (nth rows 2))))
      (is (= 0 (:task (nth rows 2)))))
    (testing "time/advance shows from -> to and no task"
      (is (= "time/advance" (:tag (nth rows 3))))
      (is (= 1 (:step (nth rows 3))))
      (is (= "0 -> 5" (:time (nth rows 3))))
      (is (nil? (:task (nth rows 3)))))
    (testing "run/completed carries step and time"
      (is (= "run/completed" (:tag (nth rows 4))))
      (is (= 1 (:step (nth rows 4))))
      (is (= 5 (:time (nth rows 4))))
      (is (nil? (:task (nth rows 4)))))))

(deftest event-rows-have-specialized-presentation-kinds
  (let [projection [:jolt.sim.value/map []]
        doc (trace/document
             [(trace/initial-event projection)
              (trace/choose-event 0 0 [0] 0)
              (trace/transition-event
               0 0 0 :sleep (trace/canonical-value :clock/wait) [] 5 projection)
              (trace/time-event 1 0 5 [0] projection)
              (trace/completed-event 1 5 projection)])
        rows (:events (report/trace->view-model doc))]
    (is (= [:jolt.sim.kind/run-initial
            :jolt.sim.kind/schedule-choice
            :jolt.sim.kind/task-transition
            :jolt.sim.kind/time-advance
            :jolt.sim.kind/run-completed]
           (mapv :kind rows)))
    (is (= "Task 0 chosen from 1 runnable task" (:summary (nth rows 1))))
    (is (= "Task 0 sleep at :clock/wait" (:summary (nth rows 2))))
    (is (= ["Operation" "Site" "Wake time"]
           (mapv :label (:fields (nth rows 2)))))
    (is (= "Virtual time advanced from 0 to 5"
           (:summary (nth rows 3))))))

(deftest default-registry-covers-every-v1-event-tag
  (let [projection [:jolt.sim.value/map []]
        events [(trace/initial-event projection)
                (trace/choose-event 0 0 [0] 0)
                (trace/transition-event
                 0 0 0 :yield (trace/canonical-value nil) [] nil projection)
                (trace/time-event 1 0 5 [0] projection)
                (trace/completed-event 1 5 projection)
                (trace/failed-event
                 1 5 0 (trace/canonical-value {:message "boom"}) projection)
                (trace/deadlock-event 1 5 [0] projection)
                (trace/step-limit-event 1 5 projection)]
        expected [:jolt.sim.kind/run-initial
                  :jolt.sim.kind/schedule-choice
                  :jolt.sim.kind/task-transition
                  :jolt.sim.kind/time-advance
                  :jolt.sim.kind/run-completed
                  :jolt.sim.kind/run-failed
                  :jolt.sim.kind/run-deadlock
                  :jolt.sim.kind/run-step-limit]]
    (is (= expected
           (mapv :kind
                 (presentation/events->rows presentation/default-registry
                                            events))))))

(deftest presentation-registry-precedence-is-site-then-op-then-tag
  (let [projection [:jolt.sim.value/map []]
        event (trace/transition-event
               0 0 7 :sleep (trace/canonical-value :acme.clock/wait)
               [] 5 projection)
        presenter (fn [label]
                    {:kind (keyword "acme.kind" label)
                     :present (fn [_] {:summary label :fields []})})
        library {:task/transition (presenter "library-tag")
                 [:task/transition :op :sleep] (presenter "library-op")}
        application {(presentation/site-key :acme.clock/wait)
                     (presenter "application-site")}
        combined (presentation/registry presentation/default-registry
                                        library application)
        site-row (presentation/present-event combined 0 event)
        op-row (presentation/present-event
                combined 1
                (assoc event 5 (trace/canonical-value :other/site)))
        tag-row (presentation/present-event
                 combined 2
                 (assoc event 4 :yield
                        5 (trace/canonical-value :other/site)))]
    (is (= "application-site" (:summary site-row)))
    (is (= (presentation/site-key :acme.clock/wait)
           (:dispatch-key site-row)))
    (is (= "acme.kind/application-site" (:kind-name site-row)))
    (is (= [] (:fields site-row)))
    (is (= "library-op" (:summary op-row)))
    (is (= [:task/transition :op :sleep] (:dispatch-key op-row)))
    (is (= "library-tag" (:summary tag-row)))
    (is (= :task/transition (:dispatch-key tag-row)))))

(deftest site-keys-support-existing-and-structured-sites
  (let [projection [:jolt.sim.value/map []]
        sites [:countdown {:ns 'demo.worker :phase :wait}]
        registry
        (presentation/registry
         presentation/default-registry
         (into {}
               (map (fn [site]
                      [(presentation/site-key site)
                       {:kind :demo.kind/site
                        :present (fn [_]
                                   {:summary (trace/canonical-edn site)
                                    :fields []})}]))
               sites))
        rows
        (mapv (fn [index site]
                (presentation/present-event
                 registry index
                 (trace/transition-event
                  index 0 index :yield (trace/canonical-value site)
                  [] nil projection)))
              (range) sites)]
    (is (= [":countdown" "{:ns demo.worker, :phase :wait}"]
           (mapv :summary rows)))
    (is (= (mapv presentation/site-key sites)
           (mapv :dispatch-key rows)))))

(deftest incremental-event-presenter-validates-once
  (let [calls (atom 0)
        event (trace/completed-event 0 0 [:jolt.sim.value/map []])
        original presentation/validate-registry!]
    (with-redefs [presentation/validate-registry!
                  (fn [registry]
                    (swap! calls inc)
                    (original registry))]
      (let [present (presentation/event-presenter
                     presentation/default-registry)]
        (present 0 event)
        (present 1 event)
        (is (= 1 @calls))))))

(deftest later-presentation-registry-overrides-win
  (let [entry (fn [label]
                {:kind (keyword "acme.kind" label)
                 :present (fn [_] {:summary label :fields []})})
        combined (presentation/registry
                  {:run/completed (entry "default")}
                  {:run/completed (entry "library")}
                  {:run/completed (entry "application")})
        event (trace/completed-event 0 0 [:jolt.sim.value/map []])]
    (is (= "application"
           (:summary (presentation/present-event combined 0 event))))))

(deftest absent-presentation-entry-falls-back-to-raw-data
  (let [event (trace/step-limit-event 3 8 [:jolt.sim.value/map []])
        row (presentation/present-event {} 4 event)]
    (is (= :jolt.sim.kind/raw-event (:kind row)))
    (is (= "Raw event run/step-limit" (:summary row)))
    (is (= event (edn/read-string (:edn row))))))

(deftest malformed-presentation-registry-and-output-fail-closed
  (let [event (trace/completed-event 0 0 [:jolt.sim.value/map []])
        bad-key (caught-data
                 #(presentation/registry
                   {[:task/transition :site :unnamespaced]
                    {:kind :acme.kind/x
                     :present (fn [_] {:summary "x" :fields []})}}))
        bad-kind (caught-data
                  #(presentation/registry
                    {:run/completed
                     {:kind :unnamespaced
                      :present (fn [_] {:summary "x" :fields []})}}))
        bad-output (caught-data
                    #(presentation/present-event
                      {:run/completed
                       {:kind :acme.kind/x
                        :present (fn [_]
                                   {:summary "x" :fields :not-a-vector})}}
                      0 event))]
    (is (= presentation/invalid-registry (:type bad-key)))
    (is (= presentation/invalid-registry (:type bad-kind)))
    (is (= presentation/invalid-presentation (:type bad-output)))))

(deftest report-option-composes-trusted-presentation-overrides
  (let [doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/completed-event 0 0 [:jolt.sim.value/map []])])
        options {:presentation-registry
                 {:run/completed
                  {:kind :acme.kind/success
                   :present (fn [_]
                              {:summary "Application finished cleanly"
                               :fields [{:label "Meaning"
                                         :value :ok}]})}}}
        vm (report/trace->view-model doc options)
        row (peek (:events vm))
        html (report/trace->html doc options)]
    (is (= :acme.kind/success (:kind row)))
    (is (= "Application finished cleanly" (:summary row)))
    (is (string/includes? html "acme.kind/success"))
    (is (string/includes? html "Application finished cleanly"))
    (is (string/includes? html "Meaning"))))

(deftest custom-presentation-text-and-values-remain-inert
  (let [hostile "<script>custom-presenter</script> & [:safe]"
        doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/completed-event 0 0 [:jolt.sim.value/map []])])
        options {:presentation-registry
                 {:run/completed
                  {:kind :acme.kind/hostile
                   :present (fn [_]
                              {:summary hostile
                               :fields [{:label hostile
                                         :value [:safe hostile]}]})}}}
        html (report/trace->html doc options)]
    (is (string/includes? html
                          "&lt;script&gt;custom-presenter&lt;/script&gt;"))
    (is (string/includes? html "&amp;"))
    (is (false? (string/includes? html hostile)))
    (is (false? (string/includes? html "<script>custom-presenter</script>")))))

(deftest event-edn-round-trips
  (let [doc (sample-doc)
        vm (report/trace->view-model doc)]
    (doseq [[index event] (map-indexed vector (:jolt.sim.trace/events doc))]
      (let [row (nth (:events vm) index)]
        (is (= index (:index row)))
        (is (= (subs (str (first event)) 1) (:tag row)))
        (is (= event (edn/read-string (:edn row))))))))

(deftest canonical-edn-round-trips
  (let [doc (sample-doc)
        vm (report/trace->view-model doc)]
    (is (= doc (trace/read-edn (:canonical-edn vm))))))

;; monitor decisions: data-only validation and rendering

(deftest monitor-decisions-pass-through-in-order
  (let [doc (sample-doc)
        decisions [{:id :trace-grammar :status :pass :detail nil :index nil}
                   {:id :no-deadlock
                    :status :inconclusive
                    :detail {:assumption :no-deadlock-observed}
                    :index nil}
                   {:id :early :status :violation :detail {:at 2} :index 2}]
        vm (report/trace->view-model doc {:monitors decisions})]
    ;; every supplied field is preserved, in order, plus deterministic text
    ;; projections used by the renderer.
    (is (= (mapv #(assoc %
                         :status-name (name (:status %))
                         :id-edn (trace/canonical-edn (:id %))
                         :detail-edn (trace/canonical-edn (:detail %)))
                 decisions)
           (:monitors vm)))
    (is (true? (:has-monitors vm)))))

(deftest monitor-decisions-are-validated
  (let [doc (sample-doc)
        valid {:id :g :status :pass :detail nil :index nil}
        bad-status (caught-data
                    #(report/trace->view-model
                      doc {:monitors [(assoc valid :status :maybe)]}))
        wrong-keys (caught-data
                    #(report/trace->view-model
                      doc {:monitors [(dissoc valid :index)]}))
        not-a-map (caught-data
                   #(report/trace->view-model doc {:monitors [:nope]}))
        not-a-vector (caught-data
                      #(report/trace->view-model
                        doc {:monitors {:id :g :status :pass :detail nil :index nil}}))
        bad-index (caught-data
                   #(report/trace->view-model
                     doc {:monitors [(assoc valid :index -1)]}))
        out-of-range (caught-data
                      #(report/trace->view-model
                        doc {:monitors [(assoc valid
                                         :index
                                         (count (:jolt.sim.trace/events doc)))]}))
        function-id (caught-data
                     #(report/trace->view-model
                       doc {:monitors [(assoc valid :id (fn [] nil))]}))]
    (is (= report/invalid-monitor-result (:type bad-status)))
    (is (= :bad-status (:reason bad-status)))
    (is (= report/invalid-monitor-result (:type wrong-keys)))
    (is (= report/invalid-monitor-result (:type not-a-map)))
    (is (= report/invalid-monitor-result (:type not-a-vector)))
    (is (= report/invalid-monitor-result (:type bad-index)))
    (is (= report/invalid-monitor-result (:type out-of-range)))
    (is (= :index-out-of-range (:reason out-of-range)))
    (is (= trace/unsupported-value (:type function-id)))))

(deftest monitor-decisions-render
  (let [doc (sample-doc)
        monitors [{:id :trace-grammar :status :pass :detail nil :index nil}
                  {:id :no-deadlock
                   :status :inconclusive
                   :detail {:assumption :no-deadlock-observed}
                   :index nil}]
        html (report/trace->html doc {:monitors monitors})]
    (is (string/includes? html "Monitor outcomes"))
    (is (string/includes? html "trace-grammar"))
    (is (string/includes? html "inconclusive"))
    (is (string/includes? html "no-deadlock-observed"))))

(deftest actual-offline-monitor-result-renders
  (let [doc (sample-doc)
        decision (monitor/check-trace-grammar doc)
        html (report/trace->html doc {:monitors [decision]})]
    (is (= :pass (:status decision)))
    (is (string/includes? html "jolt.sim.monitor/trace-grammar"))
    (is (string/includes? html "status-pass"))))

;; hostile strings stay inert text

(deftest hostile-trace-strings-are-escaped
  (let [hostile "<script>alert(1)</script> & \"quoted\" 'single' </iframe>"
        doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/failed-event 0 0 0
                                  [:jolt.sim.value/string hostile]
                                  [:jolt.sim.value/map []])])
        html (report/trace->html doc)]
    (is (string/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (string/includes? html "&amp;"))
    ;; The rendered value is canonical EDN: EDN contributes the backslashes,
    ;; then Selmer HTML-escapes each quote.
    (is (string/includes? html "\\&quot;quoted\\&quot;"))
    (is (string/includes? html "&#39;single&#39;"))
    (is (string/includes? html "&lt;/iframe&gt;"))
    (is (false? (string/includes? html "<script>alert(1)</script>")))
    (is (false? (string/includes? html "</iframe>")))))

(deftest hostile-monitor-detail-is-escaped
  (let [doc (sample-doc)
        hostile "<script>alert(1)</script> & \"quoted\" 'single' </iframe>"
        monitors [{:id :hostile
                   :status :violation
                   :detail hostile
                   :index 2}]
        html (report/trace->html doc {:monitors monitors})]
    (is (string/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (string/includes? html "&amp;"))
    ;; The rendered value is canonical EDN: EDN contributes the backslashes,
    ;; then Selmer HTML-escapes each quote.
    (is (string/includes? html "\\&quot;quoted\\&quot;"))
    (is (string/includes? html "&#39;single&#39;"))
    (is (string/includes? html "&lt;/iframe&gt;"))
    (is (false? (string/includes? html "<script>alert(1)</script>")))
    (is (false? (string/includes? html "</iframe>")))))

(deftest selmer-safe-sentinel-in-monitor-detail-is-inert
  (let [doc (sample-doc)
        hostile "<script>safe-sentinel</script>"
        monitors [{:id :hostile-safe-sentinel
                   :status :violation
                   :detail [:safe hostile]
                   :index 2}]
        html (report/trace->html doc {:monitors monitors})]
    (is (string/includes?
         html
         "[:safe &quot;&lt;script&gt;safe-sentinel&lt;/script&gt;&quot;]"))
    (is (false? (string/includes? html hostile)))))

(deftest report-forces-escaping-when-selmer-default-is-disabled
  (let [hostile "<script>outside-default</script>"
        doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/failed-event 0 0 0
                                  [:jolt.sim.value/string hostile]
                                  [:jolt.sim.value/map []])])
        html (selmer-util/without-escaping
               (report/trace->html doc))]
    (is (string/includes? html
                          "&lt;script&gt;outside-default&lt;/script&gt;"))
    (is (false? (string/includes? html hostile)))))

;; rendering determinism and self-containment

(deftest rendering-is-byte-identical
  (let [doc (sample-doc)
        options {:monitors [{:id :g :status :pass :detail nil :index nil}]}
        html1 (report/trace->html doc options)
        html2 (report/trace->html doc options)]
    (is (string? html1))
    (is (pos? (count html1)))
    (is (= html1 html2))))

(deftest nested-monitor-maps-render-byte-identically
  (let [doc (sample-doc)
        decision (fn [detail]
                   {:id {:z 9 :a 1}
                    :status :violation
                    :detail detail
                    :index 1})
        html1 (report/trace->html
               doc {:monitors [(decision (array-map :z 9 :a 1))]})
        html2 (report/trace->html
               doc {:monitors [(decision (array-map :a 1 :z 9))]})]
    (is (= html1 html2))
    (is (string/includes? html1 "{:a 1, :z 9}"))))

(deftest report-is-self-contained
  (let [html (report/trace->html (sample-doc))]
    (is (string/includes? html "<style>"))
    (is (string/includes? html "jolt-sim trace report"))
    (is (false? (string/includes? html "<link")))
    (is (false? (string/includes? html "http://")))
    (is (false? (string/includes? html "https://")))))

;; Retained-trace navigation: stable anchors, first/prev/next/last, tag/task/
;; site/virtual-time groups, and quick terminal/failure/monitor links. Every
;; target is a same-document fragment so the report stays navigable under a
;; no-script content security policy (Ripple's bare-sandbox iframe).

(defn- event-anchor-ids [html]
  (set (map parse-long (map second (re-seq #"id=\"evt-(\d+)\"" html)))))

(defn- event-anchor-hrefs [html]
  (set (map parse-long (map second (re-seq #"href=\"#evt-(\d+)\"" html)))))

(defn- all-fragment-targets [html]
  (set (map second (re-seq #"href=\"#([^\"]+)\"" html))))

(defn- all-rendered-ids [html]
  (set (map second (re-seq #"id=\"([^\"]+)\"" html))))

(deftest trace-index-positions-are-stable-at-boundaries
  (let [p (trace-index/positions 3)]
    (is (= 3 (count p)))
    (is (= ["evt-0" "evt-1" "evt-2"] (mapv :anchor p)))
    (is (= ["#evt-0" "#evt-1" "#evt-2"] (mapv :href p)))
    (is (nil? (:prev-href (nth p 0))))
    (is (= "#evt-1" (:next-href (nth p 0))))
    (is (nil? (:next-href (nth p 2))))
    (is (= "#evt-0" (:first-href (nth p 1))))
    (is (= "#evt-2" (:last-href (nth p 1))))
    (is (= 0 (:prev-index (nth p 1))))
    (is (= 2 (:position (nth p 1))))
    (is (= 3 (:total (nth p 1))))))

(deftest trace-index-monitor-targets-are-sorted-and-distinct
  (is (= [{:index 0 :href "#evt-0"}]
         (trace-index/monitor-targets [0 0 nil])))
  (is (= [{:index 2 :href "#evt-2"} {:index 5 :href "#evt-5"}]
         (trace-index/monitor-targets [5 nil 2 2]))))

(deftest view-model-event-rows-carry-stable-navigation
  (let [doc (sample-doc)
        rows (:events (report/trace->view-model doc))]
    (is (= 8 (count rows)))
    (doseq [i (range 8)]
      (is (= (str "evt-" i) (:anchor (nth rows i))))
      (is (= (str "#evt-" i) (:href (nth rows i)))))
    (let [first-row (nth rows 0)
          last-row (nth rows 7)
          mid-row (nth rows 3)]
      (is (nil? (:prev-href (:nav first-row))))
      (is (= "#evt-1" (:next-href (:nav first-row))))
      (is (nil? (:next-href (:nav last-row))))
      (is (= "#evt-6" (:prev-href (:nav last-row))))
      (is (= "#evt-0" (:first-href (:nav mid-row))))
      (is (= "#evt-7" (:last-href (:nav mid-row))))
      (is (= "#evt-2" (:prev-href (:nav mid-row))))
      (is (= "#evt-4" (:next-href (:nav mid-row))))
      (is (= 4 (:position (:nav mid-row))))
      (is (= 8 (:total (:nav mid-row)))))))

(deftest view-model-index-groups-are-deterministic-and-ordered
  (let [doc (sample-doc)
        vm1 (report/trace->view-model doc)
        vm2 (report/trace->view-model doc)]
    (is (= (:tag-groups vm1) (:tag-groups vm2)))
    (is (= (:task-groups vm1) (:task-groups vm2)))
    (is (= (:site-groups vm1) (:site-groups vm2)))
    (is (= (:time-groups vm1) (:time-groups vm2)))
    (is (= [":run/completed" ":run/initial" ":schedule/choose" ":task/transition"]
           (mapv :key-edn (:tag-groups vm1))))
    (is (= [1 1 3 3] (mapv :count (:tag-groups vm1))))
    (is (= ["0"] (mapv :key-edn (:task-groups vm1))))
    (is (= [6] (mapv :count (:task-groups vm1))))
    (is (= [":countdown" ":finish"] (mapv :key-edn (:site-groups vm1))))
    (is (= [2 1] (mapv :count (:site-groups vm1))))
    (is (= ["0"] (mapv :key-edn (:time-groups vm1))))
    (is (= [7] (mapv :count (:time-groups vm1))))
    (let [transition-group
          (some #(when (= ":task/transition" (:key-edn %)) %)
                (:tag-groups vm1))]
      (is (= [2 4 6] (mapv :index (:events transition-group))))
      (is (= ["#evt-2" "#evt-4" "#evt-6"]
             (mapv :href (:events transition-group)))))))

(deftest time-groups-index-each-virtual-time
  (let [doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/choose-event 0 0 [0] 0)
              (trace/time-event 1 0 5 [0] [:jolt.sim.value/map []])
              (trace/completed-event 1 5 [:jolt.sim.value/map []])])
        time-groups (:time-groups (report/trace->view-model doc))]
    ;; schedule/choose (idx 1) and time/advance (idx 2, indexed at from-time 0)
    ;; share time 0; run/completed (idx 3) is at time 5.
    (is (= ["0" "5"] (mapv :key-edn time-groups)))
    (is (= [2 1] (mapv :count time-groups)))
    (is (= [1 2] (mapv :index (:events (nth time-groups 0)))))))

(deftest site-groups-keep-colliding-readable-values-distinct
  (let [bytes-site (trace/canonical-value (byte-array [1]))
        vector-site (trace/canonical-value [:jolt.sim.value/bytes [1]])
        events [(trace/transition-event
                 0 0 0 :yield bytes-site [] nil [:jolt.sim.value/map []])
                (trace/transition-event
                 1 0 0 :yield vector-site [] nil [:jolt.sim.value/map []])]
        groups (trace-index/site-groups events)]
    (is (= 2 (count groups)))
    (is (= [1 1] (mapv :count groups)))
    (is (= 2 (count (set (map :canonical-key-edn groups)))))))

(deftest view-model-quick-targets-pin-terminal-and-failure-events
  (let [doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/failed-event 0 0 0
                                  [:jolt.sim.value/string "boom"]
                                  [:jolt.sim.value/map []])])
        vm (report/trace->view-model doc)
        html (report/trace->html doc)]
    (is (= [{:index 1 :tag "run/failed" :href "#evt-1"}]
           (:failure (:quick vm))))
    ;; :run/failed is itself terminal, so it appears under Terminal too.
    (is (= [{:index 1 :tag "run/failed" :href "#evt-1"}]
           (:terminal (:quick vm))))
    (is (true? (:has-failure (:quick vm))))
    (is (true? (:has-terminal (:quick vm))))
    (is (string/includes? html "Failures:"))
    (is (string/includes? html "run/failed #1"))))

(deftest view-model-quick-targets-omit-empty-sections
  (let [doc (sample-doc)
        vm (report/trace->view-model doc)
        html (report/trace->html doc)]
    (is (= [{:index 7 :tag "run/completed" :href "#evt-7"}]
           (:terminal (:quick vm))))
    (is (= [] (:failure (:quick vm))))
    (is (false? (:has-failure (:quick vm))))
    (is (true? (:has-terminal (:quick vm))))
    (is (false? (:has-monitor-targets (:quick vm))))
    (is (string/includes? html "Quick navigation"))
    (is (false? (string/includes? html "Failures:")))
    (is (false? (string/includes? html "Monitor-flagged")))))

(deftest view-model-monitor-targets-dedupe-and-drop-nil-indices
  (let [doc (sample-doc)
        decisions [{:id :a :status :pass :detail nil :index nil}
                   {:id :b :status :violation :detail {:at 2} :index 2}
                   {:id :c :status :violation :detail {:at 2} :index 2}
                   {:id :d :status :violation :detail {:at 5} :index 5}]
        vm (report/trace->view-model doc {:monitors decisions})]
    (is (= [{:index 2 :href "#evt-2"} {:index 5 :href "#evt-5"}]
           (:monitor-targets (:quick vm))))
    (is (true? (:has-monitor-targets (:quick vm))))))

(deftest view-model-handles-a-minimal-single-event-trace
  (let [doc (trace/document [(trace/initial-event [:jolt.sim.value/map []])])
        vm (report/trace->view-model doc)
        html (report/trace->html doc)
        row (first (:events vm))]
    (is (= 1 (:event-count vm)))
    (is (true? (:has-nav vm)))
    (is (true? (:has-tag-groups vm)))
    (is (false? (:has-task-groups vm)))
    (is (false? (:has-site-groups vm)))
    (is (false? (:has-time-groups vm)))
    (is (false? (:has-terminal (:quick vm))))
    (is (false? (:has-failure (:quick vm))))
    (is (false? (get-in vm [:nav :has-multiple])))
    ;; first == last == self; no prev/next target is invented.
    (is (= "#evt-0" (get-in vm [:nav :first :href])))
    (is (= "#evt-0" (get-in vm [:nav :last :href])))
    (is (nil? (:prev-href (:nav row))))
    (is (nil? (:next-href (:nav row))))
    ;; the trace index renders by-tag only; empty indexes are simply absent.
    (is (string/includes? html "Trace index"))
    (is (string/includes? html "By tag"))
    (is (false? (string/includes? html "By task")))
    (is (false? (string/includes? html "By site")))))

(deftest navigation-renders-byte-identically
  (let [doc (sample-doc)
        options {:monitors [{:id :g :status :pass :detail nil :index 2}]}
        html1 (report/trace->html doc options)
        html2 (report/trace->html doc options)]
    (is (= html1 html2))
    (is (string/includes? html1 "Quick navigation"))
    (is (string/includes? html1 "Monitor-flagged"))))

(deftest index-escapes-hostile-site-strings
  (let [hostile "<script>site</script>"
        doc (trace/document
             [(trace/initial-event [:jolt.sim.value/map []])
              (trace/transition-event 0 0 0 :yield
                                       (trace/canonical-value hostile)
                                       [] nil [:jolt.sim.value/map []])
              (trace/completed-event 0 0 [:jolt.sim.value/map []])])
        html (report/trace->html doc)]
    (is (string/includes? html "By site"))
    (is (string/includes? html "&lt;script&gt;site&lt;/script&gt;"))
    (is (false? (string/includes? html hostile)))
    ;; hostile text cannot spoof an anchor id or href attribute.
    (is (string/includes? html "id=\"evt-1\""))
    (is (string/includes? html "href=\"#evt-1\""))))

(deftest every-event-anchor-forms-a-complete-in-range-set
  (let [doc (sample-doc)
        n (count (:jolt.sim.trace/events doc))
        html (report/trace->html doc)
        ids (event-anchor-ids html)
        hrefs (event-anchor-hrefs html)]
    (is (= (set (range n)) ids))
    (is (every? #(< % n) hrefs))
    (is (every? #(contains? ids %) hrefs))))

(deftest every-navigation-href-resolves-to-a-rendered-anchor
  (let [html (report/trace->html (sample-doc))
        ids (all-rendered-ids html)
        targets (all-fragment-targets html)]
    (is (contains? ids "nav"))
    (is (contains? ids "idx-tag"))
    (is (contains? ids "idx-task"))
    (is (contains? ids "idx-site"))
    (is (contains? ids "idx-time"))
    (is (contains? ids "evt-0"))
    (is (contains? ids "evt-7"))
    (is (every? #(contains? ids %) targets))
    ;; the report carries no script-driven navigation and no off-document href.
    (is (false? (string/includes? html "href=\"http")))))

;; -main entry point

(deftest main-writes-a-report
  (let [input (temp-path ".edn")
        output (temp-path ".html")]
    (spit input (pr-str (sample-doc)))
    (report/-main input output)
    (let [html (slurp output)]
      (is (string/includes? html "jolt-sim trace report"))
      (is (string/includes? html "trace version 1")))
    (.delete (io/file input))
    (.delete (io/file output))))

(deftest main-defaults-output-path
  (let [input (temp-path ".edn")
        default-output (str (subs input 0 (- (count input) 4)) ".html")]
    (spit input (pr-str (sample-doc)))
    (report/-main input)
    (is (string/includes? (slurp default-output) "jolt-sim trace report"))
    (.delete (io/file input))
    (.delete (io/file default-output))))

(deftest main-refuses-input-output-alias
  (let [input (temp-path ".edn")]
    (spit input (pr-str (sample-doc)))
    (let [data (caught-data #(report/-main input input))]
      (is (= report/invalid-arguments (:type data)))
      (is (= :input-output-alias (:reason data))))
    ;; the input file is untouched
    (is (= (pr-str (sample-doc)) (slurp input)))
    (.delete (io/file input))))

(deftest main-refuses-normalized-existing-alias
  (let [input-file (io/file (temp-path ".edn"))
        input (str input-file)
        normalized-alias (str (.getParent input-file) "/./"
                              (.getName input-file))]
    (spit input (pr-str (sample-doc)))
    (let [data (caught-data #(report/-main input normalized-alias))]
      (is (= report/invalid-arguments (:type data)))
      (is (= :input-output-alias (:reason data))))
    (is (= (pr-str (sample-doc)) (slurp input)))
    (.delete input-file)))

(deftest main-fails-closed-on-invalid-input
  (let [input (temp-path ".edn")
        output (temp-path ".html")]
    ;; temp-path reserves a unique name by creating it; remove that placeholder
    ;; so this assertion proves -main never creates or mutates its destination.
    (.delete (io/file output))
    (spit input "not a trace document")
    (let [data (caught-data #(report/-main input output))]
      (is (= trace/invalid-document (:type data))))
    (is (false? (.exists (io/file output))))
    (.delete (io/file input))
    (.delete (io/file output))))

(deftest main-rejects-wrong-argument-count
  (let [data (caught-data #(report/-main "a.edn" "b.html" "c.html"))]
    (is (= report/invalid-arguments (:type data)))
    (is (= :wrong-argument-count (:reason data)))))

;; Case/Outcome reports: a separate validated path through
;; jolt.sim.case-outcome, never through the cooperative trace renderer.

(defn- whole-app-result []
  ;; Ten currently used known sections plus one forward key. Report v2 must
  ;; render both categories without changing the completed result.
  {:application {:commands 1 :payload (byte-array [0 255 128])}
   :http {:status 200 :body-octets 3}
   :receiver {:requests 1 :server-errors 0}
   :routes {:count 12 :all-handled? true}
   :sqlite {:statements 4 :clean? true}
   :capacity {:stream 8 :pipe 1}
   :fault {:attempts 0 :firings 0}
   :admission {:plan :receiver-poll-then-http-poll}
   :schedule {:plan :receiver-poll-then-http-poll}
   :clean? {:memory true :sqlite true :posix true}
   :unregistered {:note :visible-only-in-complete-value}})

(defn- case-outcome-monitors []
  [{:id :trace-grammar :status :pass :detail nil :index nil}
   {:id :outbox/at-least-once-delivery
    :status :violation
    :detail {:deliveries 0 :expected 1}
    :index 41}
   {:id :outbox/no-duplicate-delivery
    :status :inconclusive
    :detail {:assumption :single-reset-observed}
    :index nil}])

(defn- case-outcome-doc []
  (case-outcome/document
   {:scenario 'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-reopen-with-capacities
    :mode :hermetic
    :input {:payload [83 69 84]
            :stream-capacity 8
            :pipe-capacity 1
            :poll-eintr-ordinal nil
            :admission-plan :receiver-poll-then-http-poll}
    :schedule [1 0]}
   {:status :completed :result (whole-app-result) :exit 0}
   (case-outcome-monitors)))

(defn- minimal-case []
  {:scenario 'a.b/c :mode :real :input {:x 1} :schedule nil})

(def ^:private ordinary-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-reopen-with-capacities)

(def ^:private retry-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-retry-recv-reset)

(def ^:private cancellation-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-cancel-before-ack-with-capacities)

(def ^:private terminal-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-terminal-boundary)

(defn- completed-scenario-doc [scenario input result]
  (case-outcome/document
   {:scenario scenario :mode :hermetic :input input :schedule nil}
   {:status :completed :result result :exit 0}
   []))

(deftest case-outcome-view-model-shape
  (let [doc (case-outcome-doc)
        vm (report/case-outcome->view-model doc)]
    (is (= report/case-outcome-view-model-version (:view-model-version vm)))
    (is (= case-outcome/version (:document-version vm)))
    (is (= "jolt.sim.fixtures.outbox-delivery-scenarios/exercise-reopen-with-capacities"
           (:scenario-name vm)))
    (is (= :hermetic (:mode vm)))
    (is (= "hermetic" (:mode-name vm)))
    (is (string? (:input-edn vm)))
    (is (string/includes? (:input-edn vm) ":payload [83 69 84]"))
    (is (false? (string/includes? (:input-edn vm) ":jolt.sim.value/map")))
    (is (= (case-outcome/restore-case doc) (:replay-coordinate vm)))
    (is (= (trace/canonical-edn (case-outcome/restore-case doc))
           (:replay-coordinate-edn vm)))
    (is (= [1 0] (:schedule vm)))
    (is (= "[1 0]" (:schedule-edn vm)))
    (is (true? (:has-schedule vm)))
    (is (= :completed (:outcome-status vm)))
    (is (= "completed" (:outcome-status-name vm)))
    (is (= 0 (:outcome-exit vm)))
    (is (= "0" (:exit-edn vm)))
    (is (true? (:has-value vm)))
    (is (string? (:value-edn vm)))
    (is (true? (:has-outbox-journey vm)))
    (is (= :ordinary (get-in vm [:outbox-journey :kind])))
    (is (false? (:has-error vm)))
    (is (false? (:has-reason vm)))
    (is (= 3 (:monitor-count vm)))
    (is (true? (:has-monitors vm)))
    (is (= [:pass :violation :inconclusive]
           (mapv :status (:monitors vm))))
    (is (string? (:canonical-edn vm)))
    (is (= doc (case-outcome/read-edn (:canonical-edn vm))))))

(deftest case-outcome-view-model-is-deterministic
  (let [doc (case-outcome-doc)]
    (is (= (report/case-outcome->view-model doc)
           (report/case-outcome->view-model doc)))))

(deftest case-outcome-rendering-is-byte-identical
  (let [doc (case-outcome-doc)
        html1 (report/case-outcome->html doc)
        html2 (report/case-outcome->html doc)]
    (is (string? html1))
    (is (pos? (count html1)))
    (is (= html1 html2))))

(deftest case-outcome-rendering-is-stable-under-map-reordering
  (let [reverse-map (fn [value] (into {} (reverse (seq value))))
        forward (case-outcome-doc)
        reordered
        (case-outcome/document
         (reverse-map
          {:scenario 'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-reopen-with-capacities
           :mode :hermetic
           :input (reverse-map {:payload [83 69 84]
                                :stream-capacity 8
                                :pipe-capacity 1
                                :poll-eintr-ordinal nil
                                :admission-plan :receiver-poll-then-http-poll})
           :schedule [1 0]})
         (reverse-map
          {:status :completed :result (reverse-map (whole-app-result)) :exit 0})
         (mapv reverse-map (case-outcome-monitors)))]
    (is (= forward reordered))
    (is (= (report/case-outcome->view-model forward)
           (report/case-outcome->view-model reordered)))
    (is (= (report/case-outcome->html forward)
           (report/case-outcome->html reordered)))))

(deftest case-outcome-result-sections
  (testing "known sections render first and every forward section follows"
    (let [vm (report/case-outcome->view-model (case-outcome-doc))
          sections (:sections vm)]
      (is (true? (:has-sections vm)))
      (is (= [":application" ":http" ":receiver" ":routes" ":sqlite"
              ":capacity" ":fault" ":admission" ":schedule" ":clean?"
              ":unregistered"]
             (mapv :key-edn sections)))
      (is (= (vec (concat (repeat 10 "known") ["forward"]))
             (mapv :section-kind-name sections)))
      (is (every? string? (mapv :edn sections)))
      (let [application (some #(when (= ":application" (:key-edn %)) (:edn %))
                              sections)]
        (is (string/includes? application
                              "[:jolt.sim.value/bytes [0 255 128]]"))))
    (let [html (report/case-outcome->html (case-outcome-doc))]
      (is (string/includes? html "<summary>:application<span"))
      (is (string/includes? html "<summary>:admission<span"))
      (is (string/includes? html "<summary>:clean?<span"))
      (is (string/includes? html "<summary>:unregistered<span"))
      (is (string/includes? html ">forward</span>"))))
  (testing "multiple forward keys use deterministic canonical order"
    (let [result {:z-forward 3
                  :clean? {:memory true}
                  :a-forward 1
                  :m-forward 2}
          doc (case-outcome/document
               (minimal-case)
               {:status :completed :result result :exit 0}
               [])
          sections (:sections (report/case-outcome->view-model doc))]
      (is (= [":clean?" ":a-forward" ":m-forward" ":z-forward"]
             (mapv :key-edn sections)))
      (is (= ["known" "forward" "forward" "forward"]
             (mapv :section-kind-name sections)))))
  (testing "absent sections are simply absent"
    (let [doc (case-outcome/document
               (minimal-case)
               {:status :completed
                :result {:clean? {:memory true} :http {:status 200}}
                :exit 0}
               [])
          vm (report/case-outcome->view-model doc)]
      (is (true? (:has-sections vm)))
      (is (= [":http" ":clean?"] (mapv :key-edn (:sections vm))))))
  (testing "a present nil section remains visible"
    (let [doc (case-outcome/document
               (minimal-case)
               {:status :completed :result {:http nil} :exit 0}
               [])
          vm (report/case-outcome->view-model doc)]
      (is (= [{:key-edn ":http"
               :known? true
               :section-kind-name "known"
               :edn "nil"}]
             (:sections vm)))))
  (testing "a non-map completed value has no sections"
    (let [doc (case-outcome/document
               (minimal-case)
               {:status :completed :result :ok :exit 0}
               [])
          vm (report/case-outcome->view-model doc)
          html (report/case-outcome->html doc)]
      (is (false? (:has-sections vm)))
      (is (= [] (:sections vm)))
      (is (false? (string/includes? html "Result detail"))))))

(deftest canonical-outbox-retry-journey-is-evidence-only
  (let [hostile "<script>retry-evidence</script>"
        result
        {:application
         {:command {:value {:payload hostile}}
          :pending-state {:outbox [{:id 1 :status :pending}]}
          :retry {:attempt-1 {:error :connection-reset}
                  :delivery {:replies [{"attempt" 2}]}}
          :marking {:row {:id 1 :status :delivered} :changed? true}
          :store-state {:outbox [{:id 1 :status :delivered}]}}}
        input {:payload [0 255]
               :stream-capacity 8
               :pipe-capacity 1
               :poll-eintr-ordinal nil}
        doc (completed-scenario-doc retry-scenario input result)
        vm (report/case-outcome->view-model doc)
        journey (:outbox-journey vm)
        html (report/case-outcome->html doc)]
    (is (true? (:has-outbox-journey vm)))
    (is (= :retry (:kind journey)))
    (is (= ["Recorded command"
            "Recorded pending state"
            "Recorded first attempt"
            "Recorded retry delivery"
            "Recorded durable marking"
            "Recorded final store state"]
           (mapv :label (:steps journey))))
    (is (= ["[:application :command]"
            "[:application :pending-state]"
            "[:application :retry :attempt-1]"
            "[:application :retry :delivery]"
            "[:application :marking]"
            "[:application :store-state]"]
           (mapv :path-edn (:steps journey))))
    (is (string/includes? html "Recorded outbox journey"))
    (is (string/includes? html "Receive-reset retry"))
    (is (string/includes? html
                          "&lt;script&gt;retry-evidence&lt;/script&gt;"))
    (is (false? (string/includes? html hostile)))))

(deftest canonical-outbox-ordinary-journey-includes-persistence-evidence
  (let [result
        {:application
         {:command {:result :accepted}
          :pending-state {:outbox [{:status :pending}]}
          :delivery {:replies [{"attempt" 1}]}
          :marking {:changed? true}
          :store-state {:outbox [{:status :delivered}]}}
         :persistence {:connection-count 2
                       :open-count 2
                       :close-count 2}}
        doc (completed-scenario-doc ordinary-scenario
                                    {:payload [1]
                                     :stream-capacity 8
                                     :pipe-capacity 1
                                     :poll-eintr-ordinal nil
                                     :admission-plan
                                     :receiver-poll-then-http-poll}
                                    result)
        journey (:outbox-journey
                 (report/case-outcome->view-model doc))]
    (is (= :ordinary (:kind journey)))
    (is (= ["Recorded command"
            "Recorded pending state"
            "Recorded delivery exchange"
            "Recorded durable marking"
            "Recorded final store state"
            "Recorded close/reopen persistence"]
           (mapv :label (:steps journey))))
    (is (= "[:persistence]" (:path-edn (peek (:steps journey)))))))

(deftest canonical-outbox-journey-preserves-cancellation-and-deadline-optionals
  (testing "a present nil cancellation evidence value remains visible"
    (let [result
          {:application
           {:command {:result :accepted}
            :pending-state {:outbox [{:status :pending}]}
            :cancel nil
            :store-state {:outbox [{:status :pending}]}}}
          doc (completed-scenario-doc cancellation-scenario
                                      {:payload []
                                       :stream-capacity 8
                                       :pipe-capacity 1
                                       :poll-eintr-ordinal nil}
                                      result)
          journey (:outbox-journey
                   (report/case-outcome->view-model doc))]
      (is (= :cancellation (:kind journey)))
      (is (= ["Recorded command"
              "Recorded pending state"
              "Recorded cancellation exchange"
              "Recorded final store state"]
             (mapv :label (:steps journey))))
      (is (= "nil" (:evidence-edn (nth (:steps journey) 2))))))
  (testing "deadline evidence projects only paths actually recorded"
    (let [action {:kind :deadline
                  :boundary :before-mark
                  :offset-nanos 0}
          result
          {:terminal {:status :deadline-exceeded
                      :action action
                      :deadline-nanos 5000000000
                      :error {:reason :operation-deadline-exceeded}}
           :boundary-log nil
           :clock {:before {:now-nanos 0}
                   :after 5000000000}
           :application nil
           :http nil
           :receiver nil
           :sqlite {:outbox {:status :pending
                             :pending? true
                             :delivered? false}}
           ;; :cleanup is intentionally absent: the report must not invent it.
           }
          doc (completed-scenario-doc terminal-scenario
                                      {:terminal-action action}
                                      result)
          vm (report/case-outcome->view-model doc)
          journey (:outbox-journey vm)]
      (is (= :terminal (:kind journey)))
      (is (= ["Recorded terminal action"
              "Recorded semantic boundaries"
              "Recorded virtual clock"
              "Recorded application evidence"
              "Recorded durable outbox state"]
             (mapv :label (:steps journey))))
      (is (= "nil" (:evidence-edn (nth (:steps journey) 1))))
      (is (= "nil" (:evidence-edn (nth (:steps journey) 3))))
      (is (not (some #(= "Recorded cleanup evidence" (:label %))
                     (:steps journey)))))))

(deftest outbox-journey-and-replay-coordinate-ignore-map-insertion-order
  (let [reverse-map (fn [value] (into {} (reverse (seq value))))
        application {:command {:result :accepted}
                     :pending-state {:outbox [{:status :pending}]}
                     :retry {:attempt-1 {:error :reset}
                             :delivery {:replies [{"attempt" 2}]}}
                     :marking {:changed? true}
                     :store-state {:outbox [{:status :delivered}]}}
        input {:payload [1 2 3]
               :stream-capacity 8
               :pipe-capacity 1
               :poll-eintr-ordinal 2}
        forward (completed-scenario-doc retry-scenario input
                                        {:application application})
        reordered
        (completed-scenario-doc
         retry-scenario
         (reverse-map input)
         (reverse-map
          {:application
           (reverse-map
            (assoc application :retry (reverse-map (:retry application))))}))]
    (is (= (report/case-outcome->view-model forward)
           (report/case-outcome->view-model reordered)))
    (is (= (report/case-outcome->html forward)
           (report/case-outcome->html reordered)))))

(deftest unknown-result-sections-are-preserved-and-escaped
  (let [hostile "<script>forward-section</script>"
        doc (completed-scenario-doc
             'a.b/forward-result
             {:x 1}
             {:http {:status 200}
              :z-forward {:value 3}
              hostile {:value hostile}
              :a-forward {:value 1}})
        vm (report/case-outcome->view-model doc)
        sections (:sections vm)
        html (report/case-outcome->html doc)]
    (is (= [":http" ":a-forward" ":z-forward"
            (trace/canonical-edn hostile)]
           (mapv :key-edn sections)))
    (is (= ["known" "forward" "forward" "forward"]
           (mapv :section-kind-name sections)))
    (is (string/includes? html
                          "&lt;script&gt;forward-section&lt;/script&gt;"))
    (is (false? (string/includes? html hostile)))))

(deftest canonical-replay-coordinate-is-visible-and-complete
  (let [case {:scenario 'a.b/replay
              :mode :hermetic
              :input {:fault :reset :payload [0 255]}
              :schedule [2 1 0]}
        doc (case-outcome/document
             case {:status :completed :result :ok :exit 0} [])
        vm (report/case-outcome->view-model doc)
        html (report/case-outcome->html doc)]
    (is (= case (:replay-coordinate vm)))
    (is (= (trace/canonical-edn case) (:replay-coordinate-edn vm)))
    (is (string/includes? html "Canonical replay coordinate"))
    (is (string/includes? html ":schedule [2 1 0]"))
    (is (string/includes? html ":fault :reset"))))

(deftest case-outcome-outcome-variants-render
  (testing "failed renders the error and no value or sections"
    (let [doc (case-outcome/document
               (minimal-case)
               {:status :failed
                :error {:kind :jolt.sim/exception
                        :class "class clojure.lang.ExceptionInfo"
                        :message "delivery failed"
                        :data {:attempt 2}}
                :exit 1}
               [])
          vm (report/case-outcome->view-model doc)
          html (report/case-outcome->html doc)]
      (is (= "failed" (:outcome-status-name vm)))
      (is (= "1" (:exit-edn vm)))
      (is (true? (:has-error vm)))
      (is (false? (:has-value vm)))
      (is (false? (:has-reason vm)))
      (is (false? (:has-sections vm)))
      (is (string/includes? html "status-failed"))
      (is (string/includes? html "delivery failed"))
      (is (string/includes? html "Error (canonical EDN)"))
      (is (false? (string/includes? html "Result value (canonical EDN)")))
      (is (false? (string/includes? html "Result detail")))))
  (testing "timeout renders the deadline reason and no value or error"
    (let [doc (case-outcome/document
               (minimal-case)
               {:status :timeout :reason :deadline :exit 124}
               [])
          vm (report/case-outcome->view-model doc)
          html (report/case-outcome->html doc)]
      (is (= "timeout" (:outcome-status-name vm)))
      (is (= "124" (:exit-edn vm)))
      (is (true? (:has-reason vm)))
      (is (= :deadline (:reason vm)))
      (is (= "deadline" (:reason-name vm)))
      (is (false? (:has-value vm)))
      (is (false? (:has-error vm)))
      (is (string/includes? html "status-timeout"))
      (is (string/includes? html "<code>deadline</code>"))
      (is (false? (string/includes? html "Error (canonical EDN)")))))
  (testing "worker-error renders the error with a nil or integer exit"
    (let [error {:kind :jolt.sim/worker-error :message "no result.edn"}
          nil-exit (case-outcome/document
                    (minimal-case)
                    {:status :worker-error :error error :exit nil}
                    [])
          int-exit (case-outcome/document
                    (minimal-case)
                    {:status :worker-error :error error :exit 137}
                    [])
          nil-vm (report/case-outcome->view-model nil-exit)
          int-vm (report/case-outcome->view-model int-exit)
          html (report/case-outcome->html nil-exit)]
      (is (= "worker-error" (:outcome-status-name nil-vm)))
      (is (nil? (:outcome-exit nil-vm)))
      (is (= "nil" (:exit-edn nil-vm)))
      (is (= "137" (:exit-edn int-vm)))
      (is (true? (:has-error nil-vm)))
      (is (false? (:has-value nil-vm)))
      (is (string/includes? html "status-worker-error"))
      (is (string/includes? html "no result.edn")))))

(deftest case-outcome-monitors-render-in-document-order
  (let [doc (case-outcome/document
             (minimal-case)
             {:status :completed :result :ok :exit 0}
             [{:id :first :status :pass :detail nil :index nil}
              {:id :second :status :violation :detail {:at 3} :index 3}
              {:id :third :status :inconclusive :detail nil :index 7}])
        vm (report/case-outcome->view-model doc)
        html (report/case-outcome->html doc)]
    (is (= [":first" ":second" ":third"]
           (mapv :id-edn (:monitors vm))))
    (let [tail (subs html (string/index-of html "Monitor outcomes"))
          i1 (string/index-of tail ":first")
          i2 (string/index-of tail ":second")
          i3 (string/index-of tail ":third")]
      (is (every? some? [i1 i2 i3]))
      (is (< i1 i2 i3))
      (is (string/includes? tail "status-pass"))
      (is (string/includes? tail "status-violation"))
      (is (string/includes? tail "status-inconclusive"))
      (is (string/includes? tail "<td class=\"num\">3</td>"))
      (is (string/includes? tail "<td class=\"num\">7</td>")))))

(deftest case-outcome-empty-monitors-render-an-explicit-note
  (let [doc (case-outcome/document
             (minimal-case)
             {:status :completed :result :ok :exit 0}
             [])
        vm (report/case-outcome->view-model doc)
        html (report/case-outcome->html doc)]
    (is (false? (:has-monitors vm)))
    (is (= 0 (:monitor-count vm)))
    (is (= [] (:monitors vm)))
    (is (string/includes? html "No monitor decisions recorded."))))

(deftest case-outcome-nil-schedule-is-explicit
  (let [doc (case-outcome/document
             (minimal-case)
             {:status :completed :result :ok :exit 0}
             [])
        vm (report/case-outcome->view-model doc)]
    (is (false? (:has-schedule vm)))
    (is (nil? (:schedule vm)))
    (is (= "nil" (:schedule-edn vm)))))

(deftest hostile-case-outcome-strings-are-escaped
  (let [hostile "<script>alert(1)</script> & \"quoted\" 'single' </iframe>"
        doc (case-outcome/document
             {:scenario 'a.b/c :mode :real :input {:note hostile} :schedule nil}
             {:status :completed :result :ok :exit 0}
             [])
        html (report/case-outcome->html doc)]
    (is (string/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (string/includes? html "&amp;"))
    ;; The rendered value is canonical EDN: EDN contributes the backslashes,
    ;; then Selmer HTML-escapes each quote.
    (is (string/includes? html "\\&quot;quoted\\&quot;"))
    (is (string/includes? html "&#39;single&#39;"))
    (is (string/includes? html "&lt;/iframe&gt;"))
    (is (false? (string/includes? html "<script>alert(1)</script>")))
    (is (false? (string/includes? html "</iframe>")))))

(deftest hostile-scenario-symbol-is-escaped
  (let [doc (case-outcome/document
             {:scenario (symbol "a.b" "<script>scenario</script>")
              :mode :real
              :input nil
              :schedule nil}
             {:status :completed :result :ok :exit 0}
             [])
        html (report/case-outcome->html doc)]
    (is (string/includes? html "a.b/&lt;script&gt;scenario&lt;/script&gt;"))
    (is (false? (string/includes? html "<script>scenario</script>")))))

(deftest hostile-case-monitor-detail-is-escaped
  (let [hostile "<script>alert(1)</script> & \"quoted\" 'single' </iframe>"
        doc (case-outcome/document
             (minimal-case)
             {:status :completed :result :ok :exit 0}
             [{:id :hostile :status :violation :detail hostile :index 2}])
        html (report/case-outcome->html doc)]
    (is (string/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (string/includes? html "&amp;"))
    (is (string/includes? html "\\&quot;quoted\\&quot;"))
    (is (string/includes? html "&#39;single&#39;"))
    (is (string/includes? html "&lt;/iframe&gt;"))
    (is (false? (string/includes? html "<script>alert(1)</script>")))
    (is (false? (string/includes? html "</iframe>")))))

(deftest selmer-safe-sentinel-in-case-monitor-detail-is-inert
  (let [hostile "<script>safe-sentinel</script>"
        doc (case-outcome/document
             (minimal-case)
             {:status :completed :result :ok :exit 0}
             [{:id :hostile-safe-sentinel
               :status :violation
               :detail [:safe hostile]
               :index 2}])
        html (report/case-outcome->html doc)]
    ;; The restored [:safe ...] shape is rendered into an inert EDN string;
    ;; the vector itself never reaches Selmer as a context value.
    (is (string/includes? html "[:safe &quot;"))
    (is (string/includes? html "&lt;script&gt;safe-sentinel&lt;/script&gt;"))
    (is (false? (string/includes? html hostile)))))

(deftest case-outcome-report-forces-escaping-when-selmer-default-is-disabled
  (let [hostile "<script>outside-default</script>"
        doc (case-outcome/document
             {:scenario 'a.b/c :mode :real :input {:note hostile} :schedule nil}
             {:status :failed :error {:message hostile} :exit 1}
             [])
        html (selmer-util/without-escaping
               (report/case-outcome->html doc))]
    (is (string/includes? html
                          "&lt;script&gt;outside-default&lt;/script&gt;"))
    (is (false? (string/includes? html hostile)))))

(deftest case-outcome-path-rejects-malformed-documents
  (let [doc (case-outcome-doc)]
    (testing "unsupported version"
      (let [data (caught-data
                  #(report/case-outcome->view-model
                    (assoc doc :jolt.sim.case-outcome/version 2)))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :unsupported-version (:reason data)))))
    (testing "not a map"
      (let [data (caught-data #(report/case-outcome->view-model "not-a-doc"))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :not-a-map (:reason data)))))
    (testing "malformed stored monitor decision"
      (let [mutant (assoc-in doc [:jolt.sim.case-outcome/monitors 0 :status]
                             :bogus)
            data (caught-data #(report/case-outcome->view-model mutant))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :invalid-monitor-status (:reason data)))))
    (testing "case-outcome->html fails the same way"
      (let [data (caught-data
                  #(report/case-outcome->html
                    (assoc doc :jolt.sim.case-outcome/version 2)))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :unsupported-version (:reason data)))))))

(deftest report-paths-reject-each-others-documents
  (let [trace-data (caught-data
                    #(report/case-outcome->view-model (sample-doc)))
        case-data (caught-data
                   #(report/trace->html (case-outcome-doc)))]
    (is (= case-outcome/invalid-document (:type trace-data)))
    (is (= :wrong-keys (:reason trace-data)))
    (is (= trace/invalid-document (:type case-data)))
    (is (= :wrong-keys (:reason case-data)))))

(deftest trace-report-rendering-is-unaffected-by-case-outcome-rendering
  (let [doc (sample-doc)
        options {:monitors [{:id :g :status :pass :detail nil :index nil}]}
        before (report/trace->html doc options)]
    (report/case-outcome->html (case-outcome-doc))
    (is (= before (report/trace->html doc options)))))

(deftest case-outcome-report-is-self-contained
  (let [html (report/case-outcome->html (case-outcome-doc))]
    (is (string/includes? html "<style>"))
    (is (string/includes? html "jolt-sim case report"))
    (is (false? (string/includes? html "<link")))
    (is (false? (string/includes? html "http://")))
    (is (false? (string/includes? html "https://")))))

;; Case/Outcome -main entry point: the explicit --case-outcome selector routes
;; to the Case/Outcome renderer; the default path stays trace-only.

(deftest main-case-outcome-writes-a-report
  (let [input (temp-path ".edn")
        output (temp-path ".html")]
    (spit input (pr-str (case-outcome-doc)))
    (report/-main report/case-outcome-selector input output)
    (let [html (slurp output)]
      (is (string/includes? html "jolt-sim case report"))
      (is (string/includes? html "exercise-reopen-with-capacities")))
    (.delete (io/file input))
    (.delete (io/file output))))

(deftest main-case-outcome-defaults-output-path
  (let [input (temp-path ".edn")
        default-output (str (subs input 0 (- (count input) 4)) ".html")]
    (spit input (pr-str (case-outcome-doc)))
    (report/-main report/case-outcome-selector input)
    (is (string/includes? (slurp default-output) "jolt-sim case report"))
    (.delete (io/file input))
    (.delete (io/file default-output))))

(deftest main-case-outcome-fails-closed-on-invalid-input
  (let [input (temp-path ".edn")
        output (temp-path ".html")]
    ;; temp-path reserves a unique name by creating it; remove that placeholder
    ;; so this assertion proves -main never creates or mutates its destination.
    (.delete (io/file output))
    (spit input "not a case-outcome document")
    (let [data (caught-data #(report/-main report/case-outcome-selector
                                           input output))]
      (is (= case-outcome/invalid-document (:type data))))
    (is (false? (.exists (io/file output))))
    (.delete (io/file input))
    (.delete (io/file output))))

(deftest main-case-outcome-refuses-input-output-alias
  (let [input (temp-path ".edn")]
    (spit input (pr-str (case-outcome-doc)))
    (let [data (caught-data #(report/-main report/case-outcome-selector
                                           input input))]
      (is (= report/invalid-arguments (:type data)))
      (is (= :input-output-alias (:reason data))))
    ;; the input file is untouched
    (is (= (pr-str (case-outcome-doc)) (slurp input)))
    (.delete (io/file input))))

(deftest main-case-outcome-rejects-trace-documents
  (let [input (temp-path ".edn")
        output (temp-path ".html")]
    (.delete (io/file output))
    (spit input (pr-str (sample-doc)))
    (let [data (caught-data #(report/-main report/case-outcome-selector
                                           input output))]
      (is (= case-outcome/invalid-document (:type data))))
    (is (false? (.exists (io/file output))))
    (.delete (io/file input))
    (.delete (io/file output))))

(deftest main-trace-rejects-case-outcome-documents
  (let [input (temp-path ".edn")
        output (temp-path ".html")]
    (.delete (io/file output))
    (spit input (pr-str (case-outcome-doc)))
    (let [data (caught-data #(report/-main input output))]
      (is (= trace/invalid-document (:type data))))
    (is (false? (.exists (io/file output))))
    (.delete (io/file input))
    (.delete (io/file output))))

(deftest main-rejects-unknown-selector
  (let [data (caught-data #(report/-main "--bogus" "a.edn"))]
    (is (= report/invalid-arguments (:type data)))
    (is (= :unknown-selector (:reason data)))))

(deftest main-case-outcome-rejects-wrong-argument-count
  (let [no-args (caught-data #(report/-main report/case-outcome-selector))
        too-many (caught-data #(report/-main report/case-outcome-selector
                                             "a.edn" "b.html" "c.html"))]
    (is (= report/invalid-arguments (:type no-args)))
    (is (= :wrong-argument-count (:reason no-args)))
    (is (= report/invalid-arguments (:type too-many)))
    (is (= :wrong-argument-count (:reason too-many)))))

(deftest main-official-run-selector-writes-only-its-read-only-report
  (let [input (temp-path ".edn")
        output (temp-path ".html")]
    (spit input (official-run/canonical-edn (official-run-doc)))
    (report/-main report/official-run-selector input output)
    (let [html (slurp output)]
      (is (string/includes? html "official Maelstrom run report"))
      (is (string/includes? html ":broadcast")))
    (spit input (trace/canonical-edn (sample-doc)))
    (let [data (caught-data
                #(report/-main report/official-run-selector input output))]
      (is (= official-run/invalid-document (:type data))))
    (.delete (io/file input))
    (.delete (io/file output))))

(deftest committed-example-reports-are-current
  (let [trace-doc
        (trace/read-edn
         (slurp "examples/cooperative-countdown-trace.edn"))
        case-doc
        (case-outcome/read-edn
         (slurp "examples/outbox-retry-case-outcome.edn"))]
    (is (= (report/trace->html trace-doc)
           (slurp "examples/cooperative-countdown-trace.html")))
    (is (= (report/case-outcome->html case-doc)
           (slurp "examples/outbox-retry-case-outcome.html")))))

(deftest generated-reports-have-no-line-end-whitespace
  (doseq [html [(report/trace->html (sample-doc))
                (report/case-outcome->html
                 (case-outcome/read-edn
                  (slurp "examples/outbox-retry-case-outcome.edn")))]]
    (is (= html (string/replace html #"[ \t]+\n" "\n")))
    (is (= html (string/replace html #"[ \t]+\z" "")))))

(deftest generic-value-topology-report-reuses-the-closed-model-and-escapes
  (let [value {:kind :example/network :hostile "<script>alert(1)</script>"}
        registry
        {:example/network
         {:kind :example.kind/topology
          :present
          (fn [source]
            {:summary "Topology <one>"
             :fields [{:label "Hostile" :value (:hostile source)}]
             :graph
             {:directed? false
              :nodes [{:id "a<&" :label "A <node>"
                       :status :example.status/ready :fields []}
                      {:id "b" :label "B" :status nil :fields []}]
              :edges [{:id "a<&--b" :from "a<&" :to "b"
                       :label "A <-> B" :status :example.status/connected
                       :fields [{:label "Packets" :value [1 2]}]}]}})}}
        vm (report/value->view-model registry value)
        html (report/value->html registry value)]
    (is (= :example.kind/topology (:kind vm)))
    (is (= ["a<&" "b"] (mapv :id (get-in vm [:graph :nodes]))))
    (is (string/includes? html "<svg"))
    (is (string/includes? html "data-node-id=\"a&lt;&amp;\""))
    (is (string/includes? html "Topology &lt;one&gt;"))
    (is (string/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (not (string/includes? html "<script>")))
    (is (= html (report/value->html registry value)))))

(deftest committed-outbox-example-is-current-ack-gated-witness
  (let [doc
        (case-outcome/read-edn
         (slurp "examples/outbox-retry-case-outcome.edn"))
        outcome (case-outcome/restore-outcome doc)
        result (:result outcome)
        application (:application result)
        pending-row (first (get-in application [:pending-state :outbox]))
        delivered-row (first (get-in application [:store-state :outbox]))]
    (is (= :completed (:status outcome)))
    (is (= 0 (:exit outcome)))
    (is (= #{:identities :command :pending-state :store-state :marking :retry}
           (set (keys application))))
    (is (= #{:attempt-1 :state-unchanged-after-failure? :delivery}
           (set (keys (:retry application)))))
    (is (= :pending (:status pending-row)))
    (is (true? (get-in application
                       [:retry :state-unchanged-after-failure?])))
    (is (= [{"attempt" 2
             "outbox-id" 1
             "type" "outbox_delivery_ok"}]
           (get-in application [:retry :delivery :replies])))
    (is (= (assoc pending-row :status :delivered) delivered-row))
    (is (= (assoc-in (:pending-state application)
                     [:outbox 0 :status]
                     :delivered)
           (:store-state application)))
    (is (= {:row delivered-row :changed? true}
           (:marking application)))
    (is (= {:plan-index 27
            :plan-count 27
            :open-dbs 0
            :active-stmts 0}
           (:sqlite result)))
    (is (= [{:id :outbox/retry-invariants
             :status :pass
             :detail nil
             :index nil}]
           (case-outcome/restore-monitors doc)))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.report-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
