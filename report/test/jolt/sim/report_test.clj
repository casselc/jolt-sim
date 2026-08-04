(ns jolt.sim.report-test
  "Focused contracts for the optional static-report dependency root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.monitor :as monitor]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]
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

(defn- temp-path [suffix]
  (str (java.io.File/createTempFile "report" suffix)))

;; trace->view-model: shape, determinism, fail-closed validation

(deftest view-model-shape
  (let [doc (sample-doc)
        vm (report/trace->view-model doc)]
    (is (= 1 (:view-model-version vm)))
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

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.report-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
