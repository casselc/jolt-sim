(ns jolt.sim.report-test
  "Focused contracts for the optional static-report dependency root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.sim.case-outcome :as case-outcome]
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

;; Case/Outcome reports: a separate validated path through
;; jolt.sim.case-outcome, never through the cooperative trace renderer.

(defn- whole-app-result []
  ;; All ten known sections plus an unknown key, which must not become a
  ;; section of its own.
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
   {:scenario 'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-with-capacities
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

(deftest case-outcome-view-model-shape
  (let [doc (case-outcome-doc)
        vm (report/case-outcome->view-model doc)]
    (is (= report/case-outcome-view-model-version (:view-model-version vm)))
    (is (= case-outcome/version (:document-version vm)))
    (is (= "jolt.sim.fixtures.outbox-delivery-scenarios/exercise-with-capacities"
           (:scenario-name vm)))
    (is (= :hermetic (:mode vm)))
    (is (= "hermetic" (:mode-name vm)))
    (is (string? (:input-edn vm)))
    (is (string/includes? (:input-edn vm) ":payload [83 69 84]"))
    (is (false? (string/includes? (:input-edn vm) ":jolt.sim.value/map")))
    (is (= [1 0] (:schedule vm)))
    (is (= "[1 0]" (:schedule-edn vm)))
    (is (true? (:has-schedule vm)))
    (is (= :completed (:outcome-status vm)))
    (is (= "completed" (:outcome-status-name vm)))
    (is (= 0 (:outcome-exit vm)))
    (is (= "0" (:exit-edn vm)))
    (is (true? (:has-value vm)))
    (is (string? (:value-edn vm)))
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
          {:scenario 'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-with-capacities
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
  (testing "all ten known sections render in the fixed order; unknown keys are not sections"
    (let [vm (report/case-outcome->view-model (case-outcome-doc))
          sections (:sections vm)]
      (is (true? (:has-sections vm)))
      (is (= ["application" "http" "receiver" "routes" "sqlite"
              "capacity" "fault" "admission" "schedule" "clean?"]
             (mapv :name sections)))
      (is (every? string? (mapv :edn sections)))
      (let [application (some #(when (= "application" (:name %)) (:edn %))
                              sections)]
        (is (string/includes? application
                              "[:jolt.sim.value/bytes [0 255 128]]"))))
    (let [html (report/case-outcome->html (case-outcome-doc))]
      (is (string/includes? html "<summary>:application</summary>"))
      (is (string/includes? html "<summary>:admission</summary>"))
      (is (string/includes? html "<summary>:clean?</summary>"))
      (is (false? (string/includes? html "<summary>:unregistered</summary>")))))
  (testing "absent sections are simply absent"
    (let [doc (case-outcome/document
               (minimal-case)
               {:status :completed
                :result {:clean? {:memory true} :http {:status 200}}
                :exit 0}
               [])
          vm (report/case-outcome->view-model doc)]
      (is (true? (:has-sections vm)))
      (is (= ["http" "clean?"] (mapv :name (:sections vm))))))
  (testing "a present nil section remains visible"
    (let [doc (case-outcome/document
               (minimal-case)
               {:status :completed :result {:http nil} :exit 0}
               [])
          vm (report/case-outcome->view-model doc)]
      (is (= [{:name "http" :edn "nil"}] (:sections vm)))))
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

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.report-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
