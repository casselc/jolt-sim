(ns jolt.sim.viewer-test
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.http.body :as http-body]
            [jolt.sim.activity :as activity]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.flow-effect-view :as flow-effect-view]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.maelstrom.official-run :as official-run]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.retained-view :as retained-view]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.viewer.eval :as viewer-eval]
            [jolt.sim.viewer.experiment :as viewer-experiment]
            [jolt.sim.viewer.remote-session :as remote-session]
            [jolt.sim.session-view :as viewer-session]
            [teensyp.client :as client]))

(def token "0123456789abcdef0123456789abcdef")
(def session-instance-id "ripple-session-instance-0001")
(def scenario 'example.viewer/replay-case)
(def other-scenario 'example.viewer/not-allowed)

(defn config []
  {:port 8788
   :capability-token token
   :max-document-bytes 4096
   :allowed-scenarios #{scenario}
   :runtime-config
   {:worker-command ["/opt/jolt" "-M:sim-worker"]
    :dir "/tmp/example-project"
    :timeout-ms 5000
    :temp-dir "/tmp/example-artifacts"}})

(defn document
  ([] (document scenario))
  ([scenario]
   (case-outcome/document
    {:scenario scenario
     :mode :hermetic
     :input {:payload [0 255]}
     :schedule [1 0]}
    {:status :completed
     :result {:application {:status :ok}}
     :exit 0}
    [{:id :example/invariant
      :status :pass
      :detail nil
      :index nil}])))

(defn official-run-document []
  (official-run/document
   {:profile :official :workload :echo :parameters {:nodes 3}}
   {:status :passed :exit 0 :official-valid? true :workload-valid? true
    :checks {:valid? true} :stats {:messages 2}}
   {:total-count 2 :truncated? false :artifact "history.edn"
    :operations [{:type :invoke :f :echo :process 0 :time 1
                  :value {:msg-id 1}}
                 {:type :ok :f :echo :process 0 :time 2
                  :value {:msg-id 1}}]}
   [{:name "history.edn" :role :history :bytes 512
     :sha256 (apply str (repeat 64 "0"))}]))

(defn body [chunks]
  (let [remaining (atom (vec chunks))]
    (reify http-body/RequestBody
      (body-recv [_]
        (let [chunk (first @remaining)]
          (swap! remaining #(if (seq %) (subvec % 1) %))
          chunk))
      (body-bytes [this]
        (loop [values []]
          (if-let [chunk (http-body/body-recv this)]
            (recur (into values (seq chunk)))
            (byte-array values))))
      (body-string [this charset]
        (String. (http-body/body-bytes this) charset)))))

(defn request
  "Builds a POST request carrying the explicitly declared document kind
  (default `:case-outcome`). Pass a nil kind to exercise the missing-kind
  rejection."
  ([uri text] (request uri text token :case-outcome))
  ([uri text supplied-token] (request uri text supplied-token :case-outcome))
  ([uri text supplied-token kind]
   {:request-method :post
    :uri uri
    :headers (cond-> {"content-type" "application/edn"
                      "content-length" (str (count (.getBytes ^String text "UTF-8")))
                      "x-jolt-sim-capability" supplied-token}
               (some? kind) (assoc "x-jolt-sim-document-kind" (name kind)))
    :body (body [(.getBytes ^String text "UTF-8")])}))

(defn get-request
  ([uri] (get-request uri token))
  ([uri supplied-token]
   {:request-method :get
    :uri uri
    :headers (if supplied-token
               {"x-jolt-sim-capability" supplied-token}
               {})}))

(defn json-post-request
  ([uri value] (json-post-request uri value token))
  ([uri value supplied-token]
   (let [text (json/write-str value)]
     {:request-method :post
      :uri uri
      :headers {"content-type" "application/json"
                "content-length" (str (count (.getBytes ^String text "UTF-8")))
                "x-jolt-sim-capability" supplied-token}
      :body (body [(.getBytes ^String text "UTF-8")])})))

(defn run-preset []
  {:id :example.viewer/outbox-run
   :label "Outbox run"
   :scenario scenario
   :profile-id :hermetic
   :schedule [1 0]
   :regimes
   [{:id :example.viewer.regime/canonical
     :label "Canonical"
     :summary "Run the canonical bounded example input."
     :scope [:example.viewer/application]
     :input {:payload [0 255]}}]
   :plan-document
   (viewer-experiment/read-edn (slurp "examples/experiment-plan.edn"))})

(defn run-config []
  (assoc (config) :run-presets [(run-preset)]))

(defn run-selection []
  {"catalogVersion" 2
   "presetId" "example.viewer/outbox-run"
   "regimeId" "example.viewer.regime/canonical"
   "scope" ["example.viewer/application"]})

(defn services [render-calls replay-calls replay-outcome]
  {:render-trace
   (fn [doc]
     (swap! render-calls conj [:trace doc])
     "<html>trace</html>")
   :render-case-outcome
   (fn [doc]
     (swap! render-calls conj [:case-outcome doc])
     "<html>case-outcome</html>")
   :replay-document
   (fn [doc runtime]
     (swap! replay-calls conj [doc runtime])
     replay-outcome)})

(defn- copy-of-length [source length]
  (let [copy (byte-array length)]
    (System/arraycopy source 0 copy 0 length)
    copy))

(defn- concat-byte-arrays [chunks]
  (let [total (reduce + 0 (map alength chunks))
        output (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (let [length (alength ^bytes chunk)]
          (System/arraycopy chunk 0 output offset length)
          (recur (rest remaining) (+ offset length)))
        output))))

(defn request-over-loopback!
  "Test-only real-loopback HTTP request helper. The timeout bounds connect,
  send, and receive independently; callers still need a process-level CI
  timeout around a complete scenario."
  ([port method uri headers body-text]
   (request-over-loopback! port method uri headers body-text 5000))
  ([port method uri headers body-text timeout-ms]
   (let [body-bytes (.getBytes ^String body-text "UTF-8")
         request-text
         (str method " " uri " HTTP/1.1\r\n"
              "Host: 127.0.0.1:" port "\r\n"
              "Connection: close\r\n"
              (apply str (map (fn [[name value]]
                                (str name ": " value "\r\n"))
                              headers))
              (when (pos? (alength body-bytes))
                (str "Content-Length: " (alength body-bytes) "\r\n"))
              "\r\n"
              body-text)
         connection (client/connect "127.0.0.1" port
                                    {:connect-timeout-ms timeout-ms})]
     (try
       (client/send-all! connection (.getBytes request-text "UTF-8")
                         {:timeout-ms timeout-ms})
       (let [scratch (byte-array 4096)]
         (loop [chunks []]
           (if-let [length (client/receive-into!
                            connection scratch 0 (alength scratch)
                            {:timeout-ms timeout-ms})]
             (recur (conj chunks (copy-of-length scratch length)))
             (String. (concat-byte-arrays chunks) "UTF-8"))))
       (finally
         (client/close! connection))))))

(def retained-frame
  {:jolt.sim.retained-view/type :frame
   :kind :jolt.sim.kind/retained-process-frame
   :protocol 1
   :instance-id "private-retained-instance"
   :status :ready
   :next-sequence 4
   :uncertain-sequence nil
   :last-receipt {:status :completed :sequence 3}
   :worker {:alive? true :exit nil}
   :diagnostics {:stdout {:bytes 0 :truncated? false :read-error? false}
                 :stderr {:bytes 17 :truncated? false :read-error? false}}})

(defn retained-result
  ([status payload] (retained-result :command status payload retained-frame))
  ([operation status payload frame]
   {:jolt.sim.retained-view/type :command-result
    :kind :jolt.sim.kind/retained-command-result
    :operation operation
    :status status
    :committed? true
    :receipt (cond-> {:status status :sequence 4}
               (= :completed status) (assoc :value payload)
               (= :failed status) (assoc :error payload))
    :frame frame
    :frame-error (when-not frame
                   {:type :example/frame-race
                    :phase :post-receipt
                    :reason :snapshot-race})}))

(defn retained-services [calls]
  (merge
   (services (atom []) (atom []) {:status :unused})
   {:read-retained-frame
    (fn []
      (swap! calls conj [:read])
      retained-frame)
    :command-retained!
    (fn [command]
      (swap! calls conj [:command command])
      (retained-result :completed {:accepted true}))
    :reconcile-retained!
    (fn []
      (swap! calls conj [:reconcile])
      (retained-result :reconcile :completed {:recovered true}
                       retained-frame))
    :terminate-retained!
    (fn []
      (swap! calls conj [:terminate])
      (assoc retained-frame
             :status :terminated
             :worker {:alive? false :exit 0}))}))

(deftest startup-config-is-closed-and-fail-closed
  (is (= 8788 (:port (viewer/validate-config! (config)))))
  (doseq [[mutation reason]
          [[#(assoc % :surprise true) :unknown-keys]
           [#(assoc % :capability-token "short") :weak-capability-token]
           [#(assoc % :port -1) :invalid-port]
           [#(assoc % :max-document-bytes 0) :invalid-max-document-bytes]
           [#(assoc % :allowed-scenarios #{'unqualified})
            :invalid-allowed-scenarios]
           [#(assoc-in % [:runtime-config :scenario] scenario)
            :runtime-coordinate-collision]
           [#(assoc-in % [:runtime-config :worker-command] [])
            :invalid-worker-command]
           [#(assoc-in % [:runtime-config :dir] "")
            :invalid-project-directory]
           [#(assoc-in % [:runtime-config :timeout-ms] 0)
            :invalid-timeout-ms]
           [#(assoc-in % [:runtime-config :artifact-dir] "/tmp/nope")
            :unknown-runtime-keys]
           [#(assoc-in % [:runtime-config :extra-env] {"OK" 1})
            :invalid-extra-env]
           [#(assoc-in % [:runtime-config :retain-completed-artifacts?] :yes)
            :invalid-retain-completed-artifacts]]]
    (let [data (try
                 (viewer/validate-config! (mutation (config)))
                 nil
                 (catch :default error (ex-data error)))]
      (is (= viewer/invalid-config (:type data)))
      (is (= reason (:reason data))))))

(deftest session-instance-id-is-optional-and-header-safe
  (is (not (contains? (viewer/validate-config! (config))
                      :session-instance-id)))
  (doseq [value [(apply str (repeat 16 "a"))
                 session-instance-id
                 (apply str (repeat 128 "Z"))]]
    (is (= value
           (:session-instance-id
            (viewer/validate-config!
             (assoc (config) :session-instance-id value))))))
  (doseq [value [nil
                 1
                 ""
                 (apply str (repeat 15 "a"))
                 (apply str (repeat 129 "a"))
                 "session instance"
                 "session/instance"
                 "session:instance"
                 "session\r\nInjected: yes"
                 "ελληνικά-session-instance"]]
    (let [data (try
                 (viewer/validate-config!
                  (assoc (config) :session-instance-id value))
                 nil
                 (catch :default error (ex-data error)))]
      (is (= viewer/invalid-config (:type data)))
      (is (= :invalid-session-instance-id (:reason data)))
      (is (= {:minimum-length 16 :maximum-length 128}
             (:detail data)))
      (is (not (string/includes? (pr-str data) "Injected"))
          "invalid header text is never reflected in diagnostics"))))

(deftest run-presets-are-closed-trusted-and-profile-consistent
  (is (= [] (:run-presets (viewer/validate-config! (config)))))
  (is (= [(run-preset)]
         (:run-presets (viewer/validate-config! (run-config)))))
  (doseq [[presets reason]
          [[{} :invalid-run-presets]
           [[(dissoc (run-preset) :schedule)] :invalid-run-preset-shape]
           [[(assoc (run-preset) :id :not-namespaced)]
            :invalid-run-preset-id]
           [[(assoc (run-preset) :label " ")] :invalid-run-preset-label]
           [[(assoc (run-preset) :scenario other-scenario)]
            :run-preset-scenario-not-allowed]
           [[(assoc (run-preset) :schedule [1 1])]
            :invalid-run-preset-schedule]
           [[(assoc (run-preset) :regimes [])] :invalid-run-regimes]
           [[(update-in (run-preset) [:regimes 0] assoc :extra true)]
            :invalid-run-regime-shape]
           [[(update (run-preset) :regimes
                     #(conj % (first %)))]
            :duplicate-run-regime-ids]
           [[(assoc-in (run-preset) [:regimes 0 :id] :not-namespaced)]
            :invalid-run-regime-id]
           [[(assoc-in (run-preset) [:regimes 0 :label] " ")]
            :invalid-run-regime-label]
           [[(assoc-in (run-preset) [:regimes 0 :label]
                      (apply str (repeat 129 "x")))]
            :invalid-run-regime-label]
           [[(assoc-in (run-preset) [:regimes 0 :summary] " ")]
            :invalid-run-regime-summary]
           [[(assoc-in (run-preset) [:regimes 0 :summary]
                      (apply str (repeat 513 "x")))]
            :invalid-run-regime-summary]
           [[(assoc-in (run-preset) [:regimes 0 :scope] [])]
            :invalid-run-regime-scope]
           [[(assoc-in (run-preset) [:regimes 0 :scope]
                      [:example.viewer/application
                       :example.viewer/application])]
            :invalid-run-regime-scope]
           [[(assoc-in (run-preset) [:regimes 0 :input] (fn [] nil))]
            :invalid-run-regime-input]
           [[(assoc (run-preset) :profile-id :other)]
            :run-preset-profile-mismatch]
           [[(assoc (run-preset) :plan-document {})]
            :invalid-run-preset-plan]
           [[(run-preset) (run-preset)] :duplicate-run-preset-ids]]]
    (let [data (try
                 (viewer/validate-config!
                  (assoc (config) :run-presets presets))
                 nil
                 (catch :default error (ex-data error)))]
      (is (= viewer/invalid-config (:type data)))
      (is (= reason (:reason data))))))

(deftest run-regime-input-is-snapshotted-at-handler-construction
  (let [payload (byte-array [0 1])
        preset (assoc-in (run-preset) [:regimes 0 :input] {:payload payload})
        submitted (atom nil)
        handler
        (viewer/make-handler
         (assoc (config) :run-presets [preset])
         {:render-trace identity
          :render-case-outcome identity
          :replay-document (fn [_ _] nil)
          :run-case (fn [runtime]
                      (reset! submitted runtime)
                      {:status :completed :exit 0})})]
    (aset-byte payload 1 (byte 127))
    (is (= 200
           (:status
            (handler
             (json-post-request
              "/api/run"
              {"version" 2
               "presetId" "example.viewer/outbox-run"
               "regimeId" "example.viewer.regime/canonical"})))))
    (is (= [0 1]
           (mapv #(bit-and (long %) 0xff)
                 (seq (get-in @submitted [:input :payload]))))
        "later mutation of the embedding caller's byte array cannot change a preset")))

(deftest run-preset-catalog-is-authenticated-and-path-free
  (let [handler (viewer/make-handler
                 (run-config)
                 {:render-trace identity
                  :render-case-outcome identity
                  :replay-document (fn [_ _] nil)})
        forbidden (handler (get-request "/api/run-presets" "wrong"))
        response (handler (get-request "/api/run-presets"))
        decoded (json/read-str (:body response))
        preset (first (get decoded "presets"))]
    (is (= 403 (:status forbidden)))
    (is (= 200 (:status response)))
    (is (= #{"version" "presets"} (set (keys decoded))))
    (is (= 2 (get decoded "version")))
    (is (= decoded (viewer/run-catalog (run-config)))
        "HTTP and alternate UIs consume the same public catalog projection")
    (is (= #{"id" "label" "profileId" "planEdn" "regimes"}
           (set (keys preset))))
    (is (= "example.viewer/outbox-run" (get preset "id")))
    (is (= "Outbox run" (get preset "label")))
    (is (= "hermetic" (get preset "profileId")))
    (is (= [{"id" "example.viewer.regime/canonical"
             "label" "Canonical"
             "summary" "Run the canonical bounded example input."
             "scope" ["example.viewer/application"]}]
           (get preset "regimes")))
    (is (= (:plan-document (run-preset))
           (viewer-experiment/read-edn (get preset "planEdn"))))
    ;; Exact object-key assertions above prove that no execution-coordinate
    ;; key crosses the JSON boundary. Do not search for the ordinary word
    ;; "input" in display text: the canonical regime summary uses it
    ;; truthfully without disclosing the trusted value.
    (doseq [private-text [(str scenario) "payload" "schedule"
                          "/tmp/example-project" "/tmp/example-artifacts"]]
      (is (not (string/includes? (:body response) private-text))))))

(deftest public-run-selection-resolves-only-the-exact-trusted-pair
  (is (= {:scenario scenario
          :input {:payload [0 255]}
          :schedule [1 0]}
         (viewer/resolve-run-selection
          (run-config)
          "example.viewer/outbox-run"
          "example.viewer.regime/canonical")))
  (doseq [[preset-id regime-id reason]
          [["example.viewer/missing"
            "example.viewer.regime/canonical"
            :preset-not-found]
           ["example.viewer/outbox-run"
            "example.viewer.regime/missing"
            :regime-not-found]]]
    (let [data (try
                 (viewer/resolve-run-selection
                  (run-config) preset-id regime-id)
                 nil
                 (catch :default error (ex-data error)))]
      (is (= :jolt.sim.viewer/run-selection-not-found (:type data)))
      (is (= reason (:reason data))))))

(deftest handler-caches-the-catalog-and-snapshots-only-the-selected-regime
  (let [second-regime
        {:id :example.viewer.regime/unrelated
         :label "Unrelated"
         :summary "An unrelated trusted input."
         :scope [:example.viewer/other-boundary]
         :input {:payload [7 8 9]}}
        cfg (assoc (config)
                   :run-presets
                   [(update (run-preset) :regimes conj second-regime)])
        paths (atom [])
        original trace/canonical-value]
    (with-redefs
     [trace/canonical-value
      (fn
        ([value]
         (swap! paths conj [])
         (original value))
        ([value path]
         (swap! paths conj path)
         (original value path)))]
      (let [handler
            (viewer/make-handler
             cfg
             {:render-trace identity
              :render-case-outcome identity
              :replay-document (fn [_ _] nil)
              :run-case (fn [_] {:status :completed :exit 0})})]
        (reset! paths [])
        (is (= 200 (:status (handler (get-request "/api/run-presets")))))
        (is (empty? (filter #(= :selected-input (last %)) @paths))
            "catalog GET reuses the startup projection")
        (reset! paths [])
        (is (= 200
               (:status
                (handler
                 (json-post-request
                  "/api/run"
                  {"version" 2
                   "presetId" "example.viewer/outbox-run"
                   "regimeId" "example.viewer.regime/canonical"})))))
        (is (= [[:run-preset
                 :example.viewer/outbox-run
                 :regime
                 :example.viewer.regime/canonical
                 :selected-input]]
               (filterv #(= :selected-input (last %)) @paths))
            "only the selected input receives a fresh per-run snapshot")))))

(deftest shell-is-static-and-does-not-disclose-the-token
  (let [handler (viewer/make-handler
                 (config)
                 {:render-trace identity
                  :render-case-outcome identity
                  :replay-document (fn [_ _] nil)})
        shell (handler {:request-method :get :uri "/"})
        script (handler {:request-method :get :uri "/viewer.js"})]
    (is (= 200 (:status shell)))
    (is (string/includes? (:body shell) "Ripple"))
    (is (not (string/includes? (:body shell) token)))
    (is (= 200 (:status script)))
    (is (string/includes? (:body script) "textContent"))
    (is (not (string/includes? (:body script) "innerHTML")))
    (is (string/includes? (:body script) "pollGeneration"))
    (is (string/includes? (:body shell) "sandbox=\"allow-same-origin\""))
    (is (not (string/includes? (:body shell) "allow-scripts")))
    (is (string/includes? (:body script) "enhanceExperimentReport"))
    (is (string/includes? (:body script) "report.contentDocument"))
    (is (string/includes? (:body script) "Selected ${type} ${id}."))
    (is (string/includes? (:body script) "matchingNodeIds"))
    (is (string/includes? (:body script) "connectedNodeIds"))
    (is (not (string/includes? (:body script)
                               "kind.value !== \"experiment-plan\"")))
    (is (string/includes? (:body script) "row.focus({preventScroll: true})"))
    (is (string/includes? (:body script)
                          "doc.getElementById(\"event-filter\")"))
    (is (string/includes? (:body script)
                          "filter.removeAttribute(\"oninput\")"))
    (is (string/includes? (:body script) "rippleTraceFilterBound"))
    (is (string/includes? (:body script)
                          "filter.setAttribute(\"aria-controls\", \"event-table\")"))
    (is (string/includes? (:body script)
                          "row.querySelector(\"details code.edn\")"))
    (is (string/includes? (:body script)
                          "#event-table tbody tr"))
    (is (string/includes? (:body script)
                          "events visible."))
    (is (string/includes? (:body shell) "id=\"session-refresh\""))
    (is (string/includes? (:body shell) "id=\"session-frame\""))
    (is (string/includes? (:body script) "fetch(\"/api/session-frame\""))
    (is (string/includes? (:body script) "X-Jolt-Sim-Journal-Cursor"))
    (is (string/includes? (:body script)
                          "X-Jolt-Sim-Session-Instance"))
    (is (string/includes? (:body script) "validSessionInstanceId"))
    (is (string/includes? (:body script) "sessionInstanceKnown"))
    (is (string/includes? (:body script)
                          "Session producer changed; local cursor"))
    (is (string/includes? (:body script) "sessionFrame.textContent = body.frameEdn"))
    (is (string/includes? (:body shell) "id=\"session-choices\""))
    (is (string/includes? (:body shell) "id=\"session-step-retry\""))
    (is (string/includes? (:body script) "Retry sends the identical command bytes"))
    (is (string/includes? (:body script) "exactCoordinate"))
    (is (string/includes? (:body script) "canonicalUnsignedDecimal"))
    (is (string/includes? (:body script)
                          "No current session frame; the last refresh failed."))
    (is (string/includes? (:body script)
                          "No current session frame; refresh from cursor zero."))
    (is (not (string/includes? (:body script) "setInterval")))
    (is (string/includes? (:body script) "file.disabled = busy"))
    (is (string/includes? (:body script) "kind.disabled = busy"))
    (is (string/includes? (:body script)
                          "X-Jolt-Sim-Document-Kind"))
    (is (string/includes? (:body shell)
                          "value=\"experiment-plan\""))
    (is (string/includes? (:body script)
                          "requestRun: () => request(\"/api/replay\")"))
    (is (string/includes? (:body script) "fetch(\"/api/run\""))
    (is (string/includes? (:body shell) "id=\"eval-form\""))
    (is (string/includes? (:body shell) "id=\"eval-transcript\""))
    (is (string/includes? (:body script) "fetch(\"/api/eval\""))
    (is (string/includes? (:body script) "validEvalResponse"))
    (is (string/includes? (:body script)
                          "outcome unknown and Ripple will not retry"))
    (is (= "no-store" (get-in shell [:headers "Cache-Control"])))
    (is (string/includes?
         (get-in shell [:headers "Content-Security-Policy"])
         "default-src 'none'"))))

(deftest ephemeral-loopback-port-is-valid
  (is (= 0 (:port (viewer/validate-config! (assoc (config) :port 0))))))

(deftest programmatic-presentation-registry-is-validated-at-startup
  (let [valid (assoc
               (config)
               :presentation-registry
               {:run/completed
                {:kind :example.kind/completed
                 :present (fn [_] {:summary "done" :fields []})}})
        invalid (assoc
                 (config)
                 :presentation-registry
                 {:run/completed
                  {:kind :not-namespaced
                   :present (fn [_] {:summary "done" :fields []})}})
        data (try
               (viewer/validate-config! invalid)
               nil
               (catch :default error (ex-data error)))]
    (is (= (:presentation-registry valid)
           (:presentation-registry (viewer/validate-config! valid))))
    (is (= viewer/invalid-config (:type data)))
    (is (= :invalid-presentation-registry (:reason data)))
    (is (= :invalid-kind (get-in data [:detail :reason])))))

(deftest programmatic-value-presentation-registry-is-validated-at-startup
  (let [registry
        {:example/value
         {:kind :example.kind/value
          :present (fn [_] {:summary "value" :fields []})}}
        valid (assoc (config) :value-presentation-registry registry)
        invalid (assoc (config) :value-presentation-registry
                       {:example/value {:kind :plain :present identity}})
        data (try
               (viewer/validate-config! invalid)
               nil
               (catch :default error (ex-data error)))]
    (is (= registry
           (:value-presentation-registry (viewer/validate-config! valid))))
    (is (= viewer/invalid-config (:type data)))
    (is (= :invalid-value-presentation-registry (:reason data)))
    (is (= :invalid-output-kind (get-in data [:detail :reason])))))

(deftest value-presentation-functions-cannot-enter-the-edn-config-path
  (let [path (str "/tmp/jolt-sim-viewer-value-registry-"
                  (java.util.UUID/randomUUID) ".edn")
        read-config (resolve 'jolt.sim.viewer/read-main-config)]
    (try
      (spit path "{:port 0 :value-presentation-registry {}}")
      (let [data (try (read-config path)
                      nil
                      (catch :default error (ex-data error)))]
        (is (= viewer/invalid-config (:type data)))
        (is (= :value-presentation-registry-programmatic-only (:reason data))))
      (finally
        (fs/delete path)))))

(defn- experiment-fixture-text []
  (slurp "examples/experiment-plan.edn"))

(defn- unsafe-process-local-plan-data []
  (let [secret "DO-NOT-RENDER-EXPERIMENT-SECRET"
        hidden (fn [& _] secret)]
    {:jolt.sim.experiment/type :jolt.sim.experiment/plan
     :jolt.sim.experiment-plan/version 1
     :experiment-id :example.experiment/outbox-v1
     :profile-id :hermetic
     :manifest
     {:jolt.sim.experiment/version 1
      :id :example.experiment/outbox-v1
      :nodes
      {:client {:ports {:http {:direction :out
                               :capabilities #{:example.http/client-v1}}}}
       :app {:ports {:http {:direction :in
                            :capabilities #{:example.http/server-v1}}}}}
      :connections
      {:command {:from [:client :http]
                 :to [:app :http]
                 :pack :example.connection/http-v1
                 :config {:credential secret :callback hidden}}}
      :profiles {:hermetic {:connections
                            {:command {:mode :simulate
                                       :params {:token secret}}}}}
      :checks [{:pack :example.check/outbox-v1
                :config {:secret secret :predicate hidden}}]}
     :connections
     {:command
      {:from [:client :http]
       :to [:app :http]
       :pack-id :example.connection/http-v1
       :capabilities {:from #{:example.http/client-v1}
                      :to #{:example.http/server-v1}}
       :mode :simulate
       :binding
       {:fields
        {:consumes {:from #{:example.http/client-v1}
                    :to #{:example.http/server-v1}}
         :handler-packs
         [{:jolt.sim.handler-pack/type :jolt.sim.handler-pack/pack
           :id :example.handler/http-v1
           :handlers {[:native-operation :send] hidden
                      [:native-operation :recv] hidden}}]
         :clock hidden
         :mechanism-probe hidden
         :history-projector hidden
         :monitor-specs [{:id :example.monitor/secret :check hidden}]
         :presentation-registry
         {:secret/event {:kind :example.kind/secret :present hidden}}
         :native-fallback #{}}}}}
     :checks [{:fields {:monitor-specs [{:id :example.check/one :check hidden}]}
                       :presentation-registry
                       {:check/event {:kind :example.kind/check :present hidden}}}]
     :runtime-config
     {:ffi-mode :hermetic
      :ffi-handlers {[:native-operation :send] hidden
                     [:native-operation :recv] hidden}
      :clock hidden}
     :mechanism-probes {:command hidden}
     :history-projectors {:command hidden}
     :monitor-specs [{:id :example.monitor/secret :check hidden}
                     {:id :example.check/one :check hidden}]
     :presentation-registry
     {:secret/event {:kind :example.kind/secret :present hidden}
      :check/event {:kind :example.kind/check :present hidden}}
     :mutable-world (atom {:secret secret})}))

(deftest experiment-plan-projection-excludes-process-local-executables-and-secrets
  (let [document (viewer-experiment/plan-data->document
                  (unsafe-process-local-plan-data))
        encoded (viewer-experiment/canonical-edn document)
        html (viewer-experiment/document->html document)]
    (is (= :example.experiment/outbox-v1 (:experiment-id document)))
    (is (= :hermetic (:profile-id document)))
    (is (= 2 (get-in document [:runtime :handler-count])))
    (is (= [{:pack-id :example.handler/http-v1 :handler-count 2}]
           (get-in document [:connections 0 :handler-owners])))
    (doseq [output [encoded html]]
      (is (not (string/includes? output "DO-NOT-RENDER")))
      (is (not (string/includes? output "credential")))
      (is (not (string/includes? output "callback")))
      (is (not (string/includes? output "mutable-world")))
      (is (not (string/includes? output "function"))))))

(deftest experiment-plan-fixture-is-strict-deterministic-and-specialized
  (let [text (experiment-fixture-text)
        document (viewer-experiment/read-edn text)
        rows (:rows (viewer-experiment/document->view-model document))
        kinds (set (map :kind rows))]
    (is (= document
           (viewer-experiment/read-edn
            (viewer-experiment/canonical-edn document))))
    (is (contains? kinds :jolt.sim.kind/experiment-identity))
    (is (contains? kinds :jolt.sim.kind/experiment-node))
    (is (contains? kinds :jolt.sim.kind/experiment-connection))
    (is (contains? kinds :jolt.sim.kind/experiment-check))
    (is (contains? kinds :jolt.sim.kind/experiment-runtime))
    (is (contains? kinds :jolt.sim.kind/experiment-controls))))

(defn- cycle-experiment-document []
  (let [document (viewer-experiment/read-edn (experiment-fixture-text))
        capability [:example.loop/v1]
        owner []]
    (viewer-experiment/validate-document!
     (-> document
         (assoc :nodes
                [{:id :app
                  :ports [{:id :in :direction :in :capabilities capability}
                          {:id :out :direction :out :capabilities capability}]}
                 {:id :client
                  :ports [{:id :in :direction :in :capabilities capability}
                          {:id :out :direction :out :capabilities capability}]}]
                :connections
                [{:id :app-to-client
                  :from [:app :out] :to [:client :in]
                  :pack-id :example.connection/loop-v1 :mode :simulate
                  :capabilities {:from capability :to capability}
                  :handler-owners owner}
                 {:id :client-to-app
                  :from [:client :out] :to [:app :in]
                  :pack-id :example.connection/loop-v1 :mode :simulate
                  :capabilities {:from capability :to capability}
                  :handler-owners owner}]
                :runtime {:ffi-mode :hermetic :handler-count 0})
         (assoc :counts
                (assoc (:counts document)
                       :nodes 2 :ports 4 :connections 2
                       :handler-packs 0 :handlers 0))))))

(deftest experiment-topology-svg-is-deterministic-accessible-and-specialized
  (let [document (viewer-experiment/read-edn (experiment-fixture-text))
        first-html (viewer-experiment/document->html document)
        second-html (viewer-experiment/document->html document)]
    (is (= first-html second-html))
    (is (string/includes? first-html "<svg role=\"img\""))
    (is (string/includes? first-html "Experiment connection topology"))
    (is (string/includes? first-html "data-node=\":app\""))
    (is (string/includes? first-html "data-node=\":client\""))
    (is (string/includes? first-html "data-connection=\":command\""))
    (is (string/includes? first-html "data-from-node=\":client\""))
    (is (string/includes? first-html "data-to-node=\":app\""))
    (is (string/includes? first-html "data-mode=\":simulate\""))
    (is (string/includes? first-html "data-pack=\":example.connection/http-v1\""))
    (is (string/includes? first-html "id=\"topology-filter\""))
    (is (string/includes? first-html "id=\"topology-mode\""))
    (is (string/includes? first-html "id=\"topology-pack\""))
    (is (string/includes? first-html ".topology-node:focus-visible rect"))
    (is (string/includes? first-html "data-entity-type=\"node\" data-entity-id=\":app\""))
    (is (string/includes? first-html "data-entity-type=\"connection\" data-entity-id=\":command\""))
    (is (string/includes? first-html "class=\"plan-row\" tabindex=\"-1\""))
    (is (string/includes? first-html ":command · :simulate · :example.connection/http-v1"))
    (is (string/includes? first-html "out :http :example.http/client-v1"))
    (is (string/includes? first-html "marker-end=\"url(#topology-arrow)\""))
    ;; The specialized textual rows remain the accessible/detail view.
    (is (string/includes? first-html "jolt.sim.kind/experiment-node"))
    (is (string/includes? first-html "jolt.sim.kind/experiment-connection"))))

(deftest experiment-topology-cycle-layout-has-stable-fallback
  (let [document (cycle-experiment-document)
        html (viewer-experiment/document->html document)]
    (is (= html (viewer-experiment/document->html document)))
    (is (string/includes? html ":app-to-client · :simulate"))
    (is (string/includes? html ":client-to-app · :simulate"))
    (is (= 2 (count (re-seq #"class=\"topology-node\"" html))))
    (is (= 2 (count (re-seq #"class=\"topology-edge\"" html))))))

(deftest experiment-topology-escapes-labels-and-has-no-active-svg-content
  (let [unsafe-id (keyword "evil" "<script>alert(1)</script>")
        unsafe-plan (assoc (unsafe-process-local-plan-data)
                           :experiment-id unsafe-id)
        document (-> (viewer-experiment/plan-data->document unsafe-plan)
                     (assoc-in [:nodes 1 :id] unsafe-id)
                     (assoc-in [:connections 0 :from 0] unsafe-id)
                     viewer-experiment/validate-document!)
        html (viewer-experiment/document->html document)
        lower (string/lower-case html)]
    (is (string/includes? html ":evil/&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (not (string/includes? html "DO-NOT-RENDER-EXPERIMENT-SECRET")))
    (doseq [active ["<script" "<foreignobject" "javascript:"
                    " onload=" " onclick=" " href=" " xlink:href="]]
      (is (not (string/includes? lower active))))))

(deftest experiment-plan-validation-fails-closed
  (let [document (viewer-experiment/read-edn (experiment-fixture-text))
        cases [(assoc-in document [:counts :nodes] 99)
               (assoc document :unexpected :value)]]
    (doseq [value cases]
      (let [data (try
                   (viewer-experiment/validate-document! value)
                   nil
                   (catch :default error (ex-data error)))]
        (is (= viewer-experiment/invalid-document (:type data)))))
    (let [data (try
                 (viewer-experiment/read-edn
                  (str (viewer-experiment/canonical-edn document) " :trailing"))
                 nil
                 (catch :default error (ex-data error)))]
      (is (= viewer-experiment/invalid-document (:type data)))
      (is (= :trailing-edn (:reason data))))))

(deftest experiment-plan-http-path-is-inspection-only
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (config)
                 (services render-calls replay-calls {:status :completed}))
        text (experiment-fixture-text)
        rendered (handler (request "/api/render" text token :experiment-plan))
        replayed (handler (request "/api/replay" text token :experiment-plan))]
    (is (= 200 (:status rendered)))
    (is (string/includes? (:body rendered) "Inspection only"))
    (is (string/includes? (:body rendered)
                          "jolt.sim.kind/experiment-connection"))
    (is (string/includes? (:body rendered) "disabled"))
    (is (= 400 (:status replayed)))
    (is (string/includes? (:body replayed)
                          ":experiment-plan-not-replayable"))
    (is (= [] @render-calls))
    (is (= [] @replay-calls))))

(deftest render-validates-before-delegating-exactly-once
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (config)
                 (services render-calls replay-calls {:status :completed}))
        doc (document)
        response (handler (request "/api/render"
                                   (case-outcome/canonical-edn doc)))]
    (is (= 200 (:status response)))
    (is (= "<html>case-outcome</html>" (:body response)))
    (is (= [[:case-outcome doc]] @render-calls))
    (is (= [] @replay-calls))))

(deftest official-maelstrom-run-is-read-only-and-bypasses-viewer-services
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (config)
                 (services render-calls replay-calls {:status :completed}))
        text (official-run/canonical-edn (official-run-document))
        rendered (handler (request "/api/render" text token
                                   :official-maelstrom-run))
        replayed (handler (request "/api/replay" text token
                                   :official-maelstrom-run))]
    (is (= 200 (:status rendered)))
    (is (string/includes? (:body rendered)
                          "official Maelstrom run report"))
    (is (string/includes? (:body rendered) ":echo"))
    (is (= 400 (:status replayed)))
    (is (string/includes? (:body replayed)
                          ":official-maelstrom-run-not-replayable"))
    (is (= [] @render-calls))
    (is (= [] @replay-calls))))

(deftest malformed-unauthorized-and-wrong-media-requests-never-delegate
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (config)
                 (services render-calls replay-calls {:status :completed}))
        malformed (handler (request "/api/render" "{:not :a-document}"))
        forbidden (handler (request "/api/render"
                                    (case-outcome/canonical-edn (document))
                                    "wrong"))
        wrong-media (handler
                     (assoc-in
                      (request "/api/render"
                               (case-outcome/canonical-edn (document)))
                      [:headers "content-type"] "text/plain"))]
    (is (= 400 (:status malformed)))
    (is (= 403 (:status forbidden)))
    (is (= 415 (:status wrong-media)))
    (is (= "close" (get-in forbidden [:headers "Connection"])))
    (is (= [] @render-calls))
    (is (= [] @replay-calls))))

(deftest missing-and-unknown-document-kinds-are-rejected-before-the-body
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (config)
                 (services render-calls replay-calls {:status :completed}))
        encoded (case-outcome/canonical-edn (document))
        missing-kind (handler (request "/api/render" encoded token nil))
        unknown-kind (handler (request "/api/render" encoded token :bogus))
        missing-kind-replay (handler (request "/api/replay" encoded token nil))]
    (is (= 400 (:status missing-kind)))
    (is (string/includes? (:body missing-kind) ":document-kind-required"))
    (is (= 400 (:status unknown-kind)))
    (is (string/includes? (:body unknown-kind) ":unknown-document-kind"))
    (is (= 400 (:status missing-kind-replay)))
    (is (string/includes? (:body missing-kind-replay) ":document-kind-required"))
    (is (= [] @render-calls))
    (is (= [] @replay-calls))))

(deftest declared-and-streamed-request-limits-fail-before-render
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (assoc (config) :max-document-bytes 8)
                 (services render-calls replay-calls {:status :completed}))
        declared (handler
                  (assoc-in (request "/api/render" "{}")
                            [:headers "content-length"] "9"))
        streamed (handler
                  {:request-method :post
                   :uri "/api/render"
                   :headers {"content-type" "application/edn"
                             "x-jolt-sim-capability" token
                             "x-jolt-sim-document-kind" "case-outcome"}
                   :body (body [(.getBytes "1234" "UTF-8")
                                (.getBytes "56789" "UTF-8")])})]
    (is (= 413 (:status declared)))
    (is (= 413 (:status streamed)))
    (is (= [] @render-calls))
    (is (= [] @replay-calls))))

(deftest replay-is-allowlisted-and-runtime-owned
  (let [render-calls (atom [])
        replay-calls (atom [])
        outcome {:status :timeout :reason :deadline :exit 124}
        config (config)
        handler (viewer/make-handler
                 config
                 (services render-calls replay-calls outcome))
        doc (document)
        response (handler (request "/api/replay"
                                   (case-outcome/canonical-edn doc)))]
    (is (= 200 (:status response)))
    (is (= outcome (edn/read-string (:body response))))
    (is (= 1 (count @replay-calls)))
    (let [[passed-doc passed-runtime] (first @replay-calls)]
      (is (= doc passed-doc))
      ;; The handler augments the trusted runtime config with its own
      ;; internal `:on-run-dir` progress-tracking observer before delegating;
      ;; every browser-visible/ambient setting stays exactly server-owned.
      (is (= (:runtime-config config) (dissoc passed-runtime :on-run-dir)))
      (is (fn? (:on-run-dir passed-runtime))))
    (is (= [] @render-calls))))

(deftest disallowed-scenario-never-replays
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (config)
                 (services render-calls replay-calls {:status :completed}))
        response (handler
                  (request "/api/replay"
                           (case-outcome/canonical-edn
                            (document other-scenario))))]
    (is (= 403 (:status response)))
    (is (= [] @replay-calls))))

(deftest replay-statuses-are-returned-without-reclassification
  (doseq [outcome [{:status :failed :error {:message "boom"} :exit 1}
                   {:status :timeout :reason :deadline :exit 124}
                   {:status :worker-error :error {:message "bad child"}
                    :exit nil}]]
    (let [handler (viewer/make-handler
                   (config)
                   {:render-trace (fn [_] "unused")
                    :render-case-outcome (fn [_] "unused")
                    :replay-document (fn [_ _] outcome)})
          response (handler
                    (request "/api/replay"
                             (case-outcome/canonical-edn (document))))]
      (is (= 200 (:status response)))
      (is (= outcome (edn/read-string (:body response)))))))

(deftest replay-progress-requires-authorization-and-starts-idle
  (let [handler (viewer/make-handler
                 (config)
                 {:render-trace (fn [_] "unused")
                  :render-case-outcome (fn [_] "unused")
                  :replay-document (fn [_ _] {:status :completed :exit 0})})
        unauthorized (handler (get-request "/api/replay-progress" "wrong"))
        idle (handler (get-request "/api/replay-progress"))]
    (is (= 403 (:status unauthorized)))
    (is (= 200 (:status idle)))
    (is (= "no-store" (get-in idle [:headers "Cache-Control"])))
    (is (string/includes? (:body idle) "\"status\":\"idle\""))))

(deftest replay-progress-observes-the-run-dir-then-the-terminal-snapshot
  (let [handler* (atom nil)
        mid-flight-progress* (atom nil)
        run-dir "/tmp/jolt-sim-viewer-progress-test-fixture-dir"
        outcome {:status :completed
                 :exit 0
                 :diagnostics {:stdout {:bytes 5 :truncated? false :text "hello"}
                               :stderr {:bytes 0 :truncated? false :text ""}}}
        handler
        (viewer/make-handler
         (config)
         {:render-trace (fn [_] "unused")
          :render-case-outcome (fn [_] "unused")
          :replay-document
          (fn [_doc runtime]
            ((:on-run-dir runtime) run-dir)
            (reset! mid-flight-progress*
                    (@handler* (get-request "/api/replay-progress")))
            outcome)})]
    (reset! handler* handler)
    (let [replay-response (handler (request "/api/replay"
                                            (case-outcome/canonical-edn (document))))
          terminal-progress (handler (get-request "/api/replay-progress"))]
      (is (= 200 (:status replay-response)))
      (is (= 200 (:status @mid-flight-progress*)))
      ;; The fixture run-dir never exists on disk, so no ready marker or
      ;; output is observable yet: the live-derived status is "starting".
      (is (string/includes? (:body @mid-flight-progress*)
                            "\"status\":\"starting\""))
      (is (string/includes? (:body @mid-flight-progress*)
                            "\"result-observed?\":false"))
      (is (= 200 (:status terminal-progress)))
      (is (string/includes? (:body terminal-progress)
                            "\"status\":\"completed\""))
      (is (string/includes? (:body terminal-progress) "\"text\":\"hello\"")))))

(deftest active-progress-milestones-survive-run-directory-cleanup
  (let [observe-var (resolve 'jolt.sim.viewer/observe-active-replay)
        temp-dir (str (fs/create-temp-dir
                       {:prefix "jolt-sim-viewer-progress-latch-"}))
        ready-path (str (fs/path temp-dir "worker-ready.edn"))
        result-path (str (fs/path temp-dir "result.edn"))
        stdout-path (str (fs/path temp-dir "stdout.log"))]
    (try
      (spit ready-path "{:ready true}\n")
      (spit result-path "{:status :completed}\n")
      (spit stdout-path "retained prefix")
      (let [observed
            (@observe-var {:phase :active
                           :run-dir temp-dir
                           :status :starting
                           :result-observed? false})]
        (fs/delete-tree temp-dir)
        (let [after-cleanup (@observe-var observed)]
          (is (= :running (:status after-cleanup)))
          (is (true? (:result-observed? after-cleanup)))
          (is (= "retained prefix" (get-in after-cleanup [:stdout :text])))))
      (finally
        (when (fs/exists? temp-dir)
          (fs/delete-tree temp-dir))))))

(deftest body-consuming-posts-share-one-admission-lease
  (let [handler* (atom nil)
        busy-response* (atom nil)
        busy-body-read? (atom false)
        outcome {:status :completed :exit 0}
        handler
        (viewer/make-handler
         (config)
         {:render-trace (fn [_] "unused")
          :render-case-outcome (fn [_] "unused")
          :replay-document
          (fn [_ _]
            ;; Re-enter while the outer replay owns the lease. This avoids a
            ;; timing-dependent concurrency test while proving that render and
            ;; replay share the gate and that rejection precedes body reads.
            (reset!
             busy-response*
             (@handler*
              {:request-method :post
               :uri "/api/render"
               :headers {"content-type" "application/edn"
                         "x-jolt-sim-capability" token
                         "x-jolt-sim-document-kind" "case-outcome"}
               :body
               (reify http-body/RequestBody
                 (body-recv [_]
                   (reset! busy-body-read? true)
                   (throw (ex-info "busy body was read" {})))
                 (body-bytes [_]
                   (reset! busy-body-read? true)
                   (throw (ex-info "busy body was read" {})))
                 (body-string [_ _]
                   (reset! busy-body-read? true)
                   (throw (ex-info "busy body was read" {}))))}))
            outcome)})]
    (reset! handler* handler)
    (let [response (handler
                    (request "/api/replay"
                             (case-outcome/canonical-edn (document))))]
      (is (= 200 (:status response)))
      (is (= outcome (edn/read-string (:body response))))
      (is (= 429 (:status @busy-response*)))
      (is (= "close" (get-in @busy-response* [:headers "Connection"])))
      (is (false? @busy-body-read?)))))

(deftest trusted-run-preset-uses-the-replay-supervisor-exactly-once
  (let [handler* (atom nil)
        calls (atom [])
        busy-response* (atom nil)
        active-progress* (atom nil)
        outcome {:status :completed
                 :exit 0
                 :artifact-dir "/tmp/private-run-artifacts"
                 :diagnostics
                 {:stdout {:bytes 2 :truncated? false :text "ok"}
                  :stderr {:bytes 0 :truncated? false :text ""}}}
        handler
        (viewer/make-handler
         (run-config)
         {:render-trace (fn [_] "unused")
          :render-case-outcome (fn [_] "unused")
          :replay-document (fn [_ _] (throw (ex-info "must not replay" {})))
          :run-case
          (fn [trusted-config]
            (swap! calls conj trusted-config)
            ;; A run owns the same body-consuming lease as replay/render.
            (reset! busy-response*
                    (@handler*
                     (request "/api/replay"
                              (case-outcome/canonical-edn (document)))))
            (reset! active-progress*
                    (@handler* (get-request "/api/replay-progress")))
            ((:on-run-dir trusted-config) "/tmp/private-run-artifacts")
            outcome)})]
    (reset! handler* handler)
    (let [response (handler
                    (json-post-request
                     "/api/run"
                     {"version" 2
                      "presetId" "example.viewer/outbox-run"
                      "regimeId" "example.viewer.regime/canonical"}))
          public-outcome (edn/read-string (:body response))
          progress (json/read-str
                    (:body (handler (get-request "/api/replay-progress"))))
          active-progress (json/read-str (:body @active-progress*))
          submitted (first @calls)]
      (is (= 200 (:status response)))
      (is (= 1 (count @calls)))
      (is (= {:scenario scenario
              :input {:payload [0 255]}
              :schedule [1 0]}
             (select-keys submitted [:scenario :input :schedule])))
      (is (= (:runtime-config (config))
             (dissoc submitted :scenario :input :schedule :on-run-dir)))
      (is (fn? (:on-run-dir submitted)))
      (is (not (contains? submitted :profile-id)))
      (is (not (contains? submitted :plan-document)))
      (is (= (dissoc outcome :artifact-dir) public-outcome))
      (is (not (string/includes? (:body response)
                                 "/tmp/private-run-artifacts")))
      (is (= 429 (:status @busy-response*)))
      (is (= "starting" (get active-progress "status")))
      (is (= (run-selection) (get active-progress "selection")))
      (is (= "completed" (get progress "status")))
      (is (= (run-selection) (get progress "selection")))
      (is (= "ok" (get-in progress ["stdout" "text"]))))))

(deftest run-preset-rejections-never-call-the-service
  (let [calls (atom 0)
        handler
        (viewer/make-handler
         (run-config)
         {:render-trace identity
          :render-case-outcome identity
          :replay-document (fn [_ _] nil)
          :run-case (fn [_] (swap! calls inc))})
        malformed
        {:request-method :post
         :uri "/api/run"
         :headers {"content-type" "application/json"
                   "x-jolt-sim-capability" token}
         :body (body [(.getBytes "{" "UTF-8")])}
        duplicate-id-json
        (str "{\"version\":2,"
             "\"presetId\":\"example.viewer/outbox-run\","
             "\"regimeId\":\"example.viewer.regime/canonical\","
             "\"regimeId\":\"example.viewer.regime/missing\"}")
        duplicate-id
        {:request-method :post
         :uri "/api/run"
         :headers {"content-type" "application/json"
                   "x-jolt-sim-capability" token}
         :body (body [(.getBytes duplicate-id-json "UTF-8")])}
        responses
        [(handler (json-post-request "/api/run"
                                    {"version" 2
                                     "presetId" "example.viewer/outbox-run"
                                     "regimeId" "example.viewer.regime/canonical"}
                                    "wrong"))
         (handler malformed)
         (handler duplicate-id)
         (handler (json-post-request "/api/run"
                                     {"version" 2
                                      "presetId" "example.viewer/outbox-run"}))
         (handler (json-post-request "/api/run"
                                     {"version" 2
                                      "presetId" "example.viewer/outbox-run"
                                      "regimeId" "not-namespaced"}))
         (handler (json-post-request "/api/run"
                                     {"version" 2
                                      "presetId" "example.viewer/missing"
                                      "regimeId" "example.viewer.regime/canonical"}))
         (handler (json-post-request "/api/run"
                                     {"version" 2
                                      "presetId" "example.viewer/outbox-run"
                                      "regimeId" "example.viewer.regime/missing"}))
         (handler (json-post-request "/api/run"
                                     {"version" 2
                                      "presetId" "example.viewer/outbox-run"
                                      "regimeId" "example.viewer.regime/canonical"
                                      "extra" true}))]]
    (is (= [403 400 400 400 400 404 404 400]
           (mapv :status responses)))
    (is (zero? @calls))))

(deftest run-route-is-absent-without-the-optional-service
  (let [body-read? (atom false)
        handler (viewer/make-handler
                 (run-config)
                 {:render-trace identity
                  :render-case-outcome identity
                  :replay-document (fn [_ _] nil)})
        response
        (handler
         {:request-method :post
          :uri "/api/run"
          :headers {"content-type" "application/json"
                    "x-jolt-sim-capability" token}
          :body (reify http-body/RequestBody
                  (body-recv [_]
                    (reset! body-read? true)
                    (throw (ex-info "unavailable route read body" {})))
                  (body-bytes [_]
                    (reset! body-read? true)
                    (throw (ex-info "unavailable route read body" {})))
                  (body-string [_ _]
                    (reset! body-read? true)
                    (throw (ex-info "unavailable route read body" {}))))})]
    (is (= 404 (:status response)))
    (is (false? @body-read?))))

(deftest eval-route-is-opt-in-closed-and-delegates-exactly-once
  (let [calls (atom [])
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) nil)
                :evaluate-form!
                (fn [form]
                  (swap! calls conj form)
                  {"version" 1 "sequence" "7" "events" []})))
        response (handler (json-post-request "/api/eval"
                                             {"version" 1
                                              "form" "(+ 20 22)"}))]
    (is (= 200 (:status response)))
    (is (= ["(+ 20 22)"] @calls))
    (is (= {"version" 1 "sequence" "7" "events" []}
           (json/read-str (:body response))))))

(deftest eval-route-rejections-never-read-or-evaluate-untrusted-input
  (let [calls (atom 0)
        base-services (assoc (services (atom []) (atom []) nil)
                             :evaluate-form! (fn [_] (swap! calls inc)))
        handler (viewer/make-handler (config) base-services)
        no-service (viewer/make-handler (config)
                                        (services (atom []) (atom []) nil))
        body-read? (atom false)
        unavailable
        (no-service
         {:request-method :post
          :uri "/api/eval"
          :headers {"content-type" "application/json"
                    "x-jolt-sim-capability" token}
          :body (reify http-body/RequestBody
                  (body-recv [_]
                    (reset! body-read? true)
                    (throw (ex-info "unavailable eval body was read" {})))
                  (body-bytes [_]
                    (reset! body-read? true)
                    (throw (ex-info "unavailable eval body was read" {})))
                  (body-string [_ _]
                    (reset! body-read? true)
                    (throw (ex-info "unavailable eval body was read" {}))))})
        duplicate-json "{\"version\":1,\"form\":\"1\",\"form\":\"2\"}"
        duplicate-request
        {:request-method :post
         :uri "/api/eval"
         :headers {"content-type" "application/json"
                   "x-jolt-sim-capability" token}
         :body (body [(.getBytes duplicate-json "UTF-8")])}
        responses
        [unavailable
         (handler (json-post-request "/api/eval"
                                     {"version" 1 "form" "1"}
                                     "wrong"))
         (handler {:request-method :post
                   :uri "/api/eval"
                   :headers {"content-type" "text/plain"
                             "x-jolt-sim-capability" token}
                   :body (body [(.getBytes "1" "UTF-8")])})
         (handler duplicate-request)
         (handler (json-post-request "/api/eval" {"version" 2 "form" "1"}))
         (handler (json-post-request "/api/eval" {"version" 1 "form" 1}))
         (handler (json-post-request "/api/eval"
                                     {"version" 1 "form" "1" "extra" true}))
         (handler {:request-method :post
                   :uri "/api/eval"
                   :headers {"content-type" "application/json"
                             "content-length" "65537"
                             "x-jolt-sim-capability" token}
                   :body (body [])})]]
    (is (= [404 403 415 400 400 400 400 413] (mapv :status responses)))
    (is (false? @body-read?))
    (is (zero? @calls))))

(deftest real-eval-session-persists-repl-state-through-the-thin-adapter
  (let [session (eval-session/start)
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) nil)
                :evaluate-form! (viewer-eval/service session)))
        evaluate
        (fn [form]
          (json/read-str
           (:body
            (handler (json-post-request "/api/eval"
                                        {"version" 1 "form" form})))))]
    (try
      (let [defined (evaluate "(def ripple-answer 42)")
            recalled (evaluate "[ripple-answer *1]")
            failed (evaluate "(throw (ex-info \"ripple-boom\" {:ui true}))")]
        (is (= "0" (get defined "sequence")))
        (is (= "user" (get-in recalled ["namespace" "after"])))
        ;; Vars are deliberately opaque at the HTTP boundary: projecting their
        ;; host printer could invoke arbitrary or unbounded printing after the
        ;; evaluation has already committed.
        (is (= "[42 \"#<class clojure.lang.Var>\"]"
               (get-in recalled ["events" 0 "printedValue"])))
        (is (true? (get-in failed ["events" 0 "exception"])))
        (is (= "2" (get failed "sequence"))))
      (finally
        (eval-session/close! session)))))

(deftest committed-evaluation-is-never-reclassified-as-a-size-failure
  (let [handler
        (viewer/make-handler
         (assoc (config) :max-document-bytes 1)
         (assoc (services (atom []) (atom []) nil)
                :evaluate-form!
                (fn [_]
                  {"version" 1
                   "sequence" "0"
                   "namespace" {"before" "user" "after" "user"}
                   "events" []})))
        response (handler (json-post-request "/api/eval"
                                             {"version" 1 "form" "42"}))]
    (is (= 200 (:status response)))
    (is (= "0" (get (json/read-str (:body response)) "sequence")))))

(deftest eval-wire-bounds-opaque-values-and-uses-display-truthfully
  (let [wire
        (viewer-eval/evaluation-wire
         {:sequence 9
          :namespace {:before "user" :after "user"}
          :events [{:tag :out :val (apply str (repeat 5000 "x"))}
                   {:tag :ret :val (range) :ns "user" :ms 1}]})
        output (first (get wire "events"))
        terminal (second (get wire "events"))]
    (is (= #{"tag" "text" "truncated"} (set (keys output))))
    (is (= 4096 (count (get output "text"))))
    (is (true? (get output "truncated")))
    (is (= #{"tag" "printedValue" "truncated" "printFailed"
             "exception" "namespace" "namespaceTruncated" "elapsedMs"}
           (set (keys terminal))))
    (is (string? (get terminal "printedValue")))
    (is (< (count (get terminal "printedValue")) 4096))
    (is (true? (get terminal "truncated")))
    (is (false? (get terminal "printFailed")))))

(deftest eval-wire-never-realizes-a-blocking-lazy-result-after-commit
  (let [release (promise)
        blocking (lazy-seq @release)
        projected
        (future
          (viewer-eval/evaluation-wire
           {:sequence 10
            :namespace {:before "user" :after "user"}
            :events [{:tag :ret :val blocking :ns "user" :ms 1}]}))]
    (try
      (let [wire (deref projected 1000 ::timeout)
            terminal (first (get wire "events"))]
        (is (not= ::timeout wire))
        (is (string/includes? (get terminal "printedValue") "LazySeq"))
        (is (true? (get terminal "truncated"))))
      (finally
        (deliver release nil)))))

(deftest eval-wire-bounds-large-scalars-and-envelope-namespaces
  (let [large (apply str (repeat 100000 "x"))
        wire
        (viewer-eval/evaluation-wire
         {:sequence 11
          :namespace {:before large :after large}
          :events [{:tag :ret :val large :ns large :ms 1}]})
        terminal (first (get wire "events"))]
    (is (= 1024 (count (get-in wire ["namespace" "before"]))))
    (is (true? (get-in wire ["namespace" "beforeTruncated"])))
    (is (= 1024 (count (get-in wire ["namespace" "after"]))))
    (is (true? (get-in wire ["namespace" "afterTruncated"])))
    (is (= 1024 (count (get terminal "namespace"))))
    (is (true? (get terminal "namespaceTruncated")))
    (is (< (count (get terminal "printedValue")) 2048))
    (is (true? (get terminal "truncated")))))

(deftest eval-wire-never-prints-an-exact-ratio-after-commit
  (let [huge-integer (reduce * (repeat 2000 10))
        huge-ratio (/ huge-integer 3)
        terminal
        (first
         (get
          (viewer-eval/evaluation-wire
           {:sequence 12
            :namespace {:before "user" :after "user"}
            :events [{:tag :ret :val huge-ratio :ns "user" :ms 1}]})
          "events"))]
    (is (string/starts-with? (get terminal "printedValue")
                             "\"#<number "))
    (is (< (count (get terminal "printedValue")) 256))
    (is (true? (get terminal "truncated")))))

(deftest closed-eval-session-is-a-definite-conflict-not-a-second-evaluation
  (let [session (eval-session/start)
        _ (eval-session/close! session)
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) nil)
                :evaluate-form! (viewer-eval/service session)))
        response (handler (json-post-request "/api/eval"
                                             {"version" 1 "form" "42"}))]
    (is (= 409 (:status response)))
    (is (= :eval-session-closed
           (:error (edn/read-string (:body response)))))))

(deftest eval-shares-the-body-consuming-admission-lease
  (let [handler* (atom nil)
        busy-response* (atom nil)
        busy-body-read? (atom false)
        handler
        (viewer/make-handler
         (config)
         (assoc
          (services (atom []) (atom []) nil)
          :evaluate-form!
          (fn [_]
            (reset!
             busy-response*
             (@handler*
              {:request-method :post
               :uri "/api/render"
               :headers {"content-type" "application/edn"
                         "x-jolt-sim-capability" token
                         "x-jolt-sim-document-kind" "case-outcome"}
               :body (reify http-body/RequestBody
                       (body-recv [_]
                         (reset! busy-body-read? true)
                         (throw (ex-info "busy body was read" {})))
                       (body-bytes [_]
                         (reset! busy-body-read? true)
                         (throw (ex-info "busy body was read" {})))
                       (body-string [_ _]
                         (reset! busy-body-read? true)
                         (throw (ex-info "busy body was read" {}))))}))
            {"version" 1 "sequence" "0" "events" []})))]
    (reset! handler* handler)
    (is (= 200 (:status
                (handler (json-post-request "/api/eval"
                                            {"version" 1 "form" "42"})))))
    (is (= 429 (:status @busy-response*)))
    (is (false? @busy-body-read?))))

(deftest unexpected-service-errors-propagate-to-the-http-error-boundary
  (let [error (ex-info "renderer defect" {:type ::renderer-defect})
        handler (viewer/make-handler
                 (config)
                 {:render-trace (fn [_] "unused")
                  :render-case-outcome (fn [_] (throw error))
                  :replay-document (fn [_ _] nil)})]
    (is (identical?
         error
         (try
           (handler (request "/api/render"
                             (case-outcome/canonical-edn (document))))
           nil
           (catch :default caught caught))))))

(deftest bounded-progress-text-caps-a-log-larger-than-the-bound-without-a-toctou-length-check
  (let [read-var (resolve 'jolt.sim.viewer/bounded-progress-text)
        limit @(resolve 'jolt.sim.viewer/progress-log-byte-limit)
        temp-dir (str (fs/create-temp-dir
                       {:prefix "jolt-sim-viewer-bounded-text-oversized-"}))
        path (str (fs/path temp-dir "stdout.log"))
        oversized (apply str (repeat (+ limit 100) \a))]
    (spit path oversized)
    (try
      (let [diagnostic (@read-var path)]
        (is (= (count oversized) (:bytes diagnostic)))
        (is (true? (:truncated? diagnostic)))
        (is (= limit (count (:text diagnostic))))
        (is (= (subs oversized 0 limit) (:text diagnostic))))
      (finally (fs/delete-tree temp-dir)))))

(deftest bounded-progress-text-tolerates-a-missing-file
  (let [read-var (resolve 'jolt.sim.viewer/bounded-progress-text)
        temp-dir (str (fs/create-temp-dir
                       {:prefix "jolt-sim-viewer-bounded-text-missing-"}))
        path (str (fs/path temp-dir "absent.log"))]
    (try
      (is (false? (fs/exists? path)))
      (let [diagnostic (@read-var path)]
        (is (= 0 (:bytes diagnostic)))
        (is (false? (:truncated? diagnostic)))
        (is (= "" (:text diagnostic))))
      (finally (fs/delete-tree temp-dir)))))

(deftest unknown-routes-do-not-read-or-run-a-document
  (let [handler (viewer/make-handler
                 (config)
                 {:render-trace (fn [_] (throw (ex-info "called" {})))
                  :render-case-outcome (fn [_] (throw (ex-info "called" {})))
                  :replay-document (fn [_ _] (throw (ex-info "called" {})))})
        response (handler {:request-method :post
                           :uri "/api/nope"
                           :headers {}
                           :body nil})]
    (is (= 404 (:status response)))))

(deftest live-loopback-server-serves-shell-and-renders-a-retained-document
  (let [server (viewer/start! (assoc (config) :port 0))]
    (try
      (let [port (:port server)
            shell (request-over-loopback! port "GET" "/" {} "")
            encoded (case-outcome/canonical-edn (document))
            rendered
            (request-over-loopback!
             port "POST" "/api/render"
             {"Content-Type" "application/edn"
              "X-Jolt-Sim-Capability" token
              "X-Jolt-Sim-Document-Kind" "case-outcome"}
             encoded)]
        (is (pos? port))
        (is (string/starts-with? shell "HTTP/1.1 200"))
        (is (string/includes? shell "Ripple"))
        (is (string/starts-with? rendered "HTTP/1.1 200"))
        (is (string/includes? rendered "example.viewer/replay-case")))
      (finally
        (viewer/stop! server)))))

(deftest command-line-main-owns-sigint-and-stops-the-server-once
  (let [stopped (promise)
        started (promise)
        fake-server {:port 8788 :stopped stopped}
        events (atom [])
        shutdown-hook (atom nil)
        read-config-var (resolve 'jolt.sim.viewer/read-main-config)
        start-var (resolve 'jolt.sim.viewer/start!)
        stop-var (resolve 'jolt.sim.viewer/stop!)
        block-var (resolve 'jolt.host/block-sigint)
        add-hook-var (resolve 'jolt.host/add-shutdown-hook)
        park-var (resolve 'jolt.host/park-until-interrupt)]
    (with-redefs-fn
      {read-config-var (fn [path]
                         (is (= "/tmp/ripple-config.edn" path))
                         (config))
       start-var (fn [validated]
                   (is (= (viewer/validate-config! (config)) validated))
                   (swap! events conj :start)
                   (deliver started true)
                   fake-server)
       stop-var (fn [server]
                  (is (= fake-server server))
                  (swap! events conj :stop)
                  (deliver stopped :stopped))
       block-var (fn [] (swap! events conj :block-sigint))
       add-hook-var (fn [hook]
                      (swap! events conj :add-shutdown-hook)
                      (reset! shutdown-hook hook))
       park-var (fn []
                  (swap! events conj :park)
                  (is (fn? @shutdown-hook))
                  (@shutdown-hook)
                  @stopped)}
      #(let [main-result (future
                           (viewer/-main "/tmp/ripple-config.edn"))]
         (is (= true (deref started 1000 ::timeout)))
         (is (= :stopped (deref main-result 1000 ::timeout)))
         (is (= [:block-sigint :start :add-shutdown-hook :park :stop]
                @events)
             "SIGINT must be blocked before workers start and shutdown once")))))

(deftest command-line-eval-flag-explicitly-owns-one-eval-session
  (let [stopped (promise)
        fake-server {:port 8788}
        fake-session (Object.)
        events (atom [])
        shutdown-hook (atom nil)
        read-config-var (resolve 'jolt.sim.viewer/read-main-config)
        start-eval-viewer-var (resolve 'jolt.sim.viewer/start-eval-session!)
        stop-var (resolve 'jolt.sim.viewer/stop!)
        eval-start-var (resolve 'jolt.sim.eval-session/start)
        eval-close-var (resolve 'jolt.sim.eval-session/close!)
        block-var (resolve 'jolt.host/block-sigint)
        add-hook-var (resolve 'jolt.host/add-shutdown-hook)
        park-var (resolve 'jolt.host/park-until-interrupt)]
    (with-redefs-fn
      {read-config-var (fn [path]
                         (is (= "/tmp/ripple-eval-config.edn" path))
                         (config))
       eval-start-var (fn []
                        (swap! events conj :eval-start)
                        fake-session)
       start-eval-viewer-var
       (fn [validated session]
         (is (= (viewer/validate-config! (config)) validated))
         (is (identical? fake-session session))
         (swap! events conj :start-eval-viewer)
         fake-server)
       stop-var (fn [server]
                  (is (= fake-server server))
                  (swap! events conj :stop)
                  (deliver stopped :stopped))
       eval-close-var (fn [_]
                        (throw (ex-info "SIGINT must not wait for eval lock" {})))
       block-var (fn [] (swap! events conj :block-sigint))
       add-hook-var (fn [hook]
                      (swap! events conj :add-shutdown-hook)
                      (reset! shutdown-hook hook))
       park-var (fn []
                  (swap! events conj :park)
                  (@shutdown-hook)
                  @stopped)}
      #(let [main-result
             (future
               (viewer/-main "--eval" "/tmp/ripple-eval-config.edn"))]
         (is (= :stopped (deref main-result 1000 ::timeout)))
         (is (= [:block-sigint :eval-start :start-eval-viewer
                 :add-shutdown-hook :park :stop]
                @events))))))

;; Real-artifact tests. The gate runs from the viewer directory (CI: cd
;; viewer && jolt -M:test), so the checked-in report examples resolve one
;; level up, exactly like the report suite's own relative example paths.

(defn- example-edn-text [name]
  (slurp (str "../report/examples/" name)))

(defn- large-document-config []
  ;; The committed outbox-retry Case/Outcome artifact is ~20 KiB, larger than
  ;; the small default test limit.
  (assoc (config) :max-document-bytes (* 1024 1024)))

(deftest render-routes-by-declared-kind-to-the-matching-service
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (large-document-config)
                 (services render-calls replay-calls {:status :completed}))
        trace-doc (trace/read-edn
                   (example-edn-text "cooperative-countdown-trace.edn"))
        case-doc (case-outcome/read-edn
                   (example-edn-text "outbox-retry-case-outcome.edn"))
        trace-response (handler (request "/api/render"
                                         (trace/canonical-edn trace-doc)
                                         token
                                         :trace))
        case-response (handler (request "/api/render"
                                        (case-outcome/canonical-edn case-doc)
                                        token
                                        :case-outcome))]
    (is (= 200 (:status trace-response)))
    (is (= "<html>trace</html>" (:body trace-response)))
    (is (= 200 (:status case-response)))
    (is (= "<html>case-outcome</html>" (:body case-response)))
    (is (= [[:trace trace-doc] [:case-outcome case-doc]] @render-calls))
    (is (= [] @replay-calls))))

(deftest trace-document-renders-through-the-real-trace-report-path
  (let [handler (viewer/make-handler (config))
        progress-before (handler (get-request "/api/replay-progress"))
        response (handler (request "/api/render"
                                   (example-edn-text
                                    "cooperative-countdown-trace.edn")
                                   token
                                   :trace))
        progress-after (handler (get-request "/api/replay-progress"))]
    (is (= 200 (:status response)))
    (is (string/includes? (:body response) "countdown"))
    (is (string/includes? (:body response) "run/completed"))
    (is (= (:body progress-before) (:body progress-after)))
    (is (string/includes? (:body progress-after) "\"status\":\"idle\""))))

(deftest ripple-uses-the-trusted-programmatic-presentation-registry
  (let [presenters
        {:run/completed
         {:kind :example.kind/success
          :present (fn [_]
                     {:summary "Example application completed"
                      :fields [{:label "Result" :value :ok}]})}}
        handler (viewer/make-handler
                 (assoc (config) :presentation-registry presenters))
        response (handler (request "/api/render"
                                   (example-edn-text
                                    "cooperative-countdown-trace.edn")
                                   token
                                   :trace))]
    (is (= 200 (:status response)))
    (is (string/includes? (:body response) "example.kind/success"))
    (is (string/includes? (:body response)
                          "Example application completed"))
    (is (string/includes? (:body response) "Result"))))

(deftest case-outcome-document-renders-through-the-real-case-outcome-report-path
  (let [handler (viewer/make-handler (large-document-config))
        response (handler (request "/api/render"
                                   (example-edn-text
                                    "outbox-retry-case-outcome.edn")
                                   token
                                   :case-outcome))]
    (is (= 200 (:status response)))
    (is (string/includes? (:body response) "outbox"))
    (is (string/includes?
         (:body response)
         "jolt.sim.fixtures.outbox-delivery-scenarios/exercise-retry-recv-reset"))))

(deftest replay-rejects-trace-documents-before-restore-or-worker-execution
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (config)
                 (services render-calls replay-calls {:status :completed}))
        response (handler (request "/api/replay"
                                   (example-edn-text
                                    "cooperative-countdown-trace.edn")
                                   token
                                   :trace))]
    (is (= 400 (:status response)))
    (is (string/includes? (:body response) ":trace-not-replayable"))
    (is (= [] @render-calls))
    (is (= [] @replay-calls))))

(deftest misdeclared-document-kind-is-rejected-by-the-declared-codec
  (let [render-calls (atom [])
        replay-calls (atom [])
        handler (viewer/make-handler
                 (large-document-config)
                 (services render-calls replay-calls {:status :completed}))
        trace-as-case (handler (request "/api/render"
                                        (example-edn-text
                                         "cooperative-countdown-trace.edn")
                                        token
                                        :case-outcome))
        case-as-trace (handler (request "/api/render"
                                        (example-edn-text
                                         "outbox-retry-case-outcome.edn")
                                        token
                                        :trace))]
    (is (= 400 (:status trace-as-case)))
    (is (string/includes? (:body trace-as-case) ":invalid-document"))
    (is (= 400 (:status case-as-trace)))
    (is (string/includes? (:body case-as-trace) ":invalid-document"))
    (is (= [] @render-calls))
    (is (= [] @replay-calls))))

;; --- Viewer-side session adapter (jolt.sim.session-view) ---
;;
;; UI-neutral read/step slice over one cooperative Session. The core logic is
;; exercised through the public API with a real Session, and through the
;; private ops seam (resolved below) where a concurrent step must be scripted
;; deterministically: bounded coherence and post-commit frame failure.

(def ^:private read-frame-ops-var
  (resolve 'jolt.sim.session-view/read-frame*))

(def ^:private step-frame-ops-var
  (resolve 'jolt.sim.session-view/step-frame*))

(defn- session-sim-config []
  {:tasks {2 (kernel/runnable :finish)
           0 (kernel/runnable :sleep)}
   :world {:seen []}
   :step (fn [{:keys [task now world]} state]
           (case state
             :sleep (-> (kernel/step-sleep :wake (+ now 5))
                        (kernel/with-world (update world :seen conj task))
                        (kernel/at-site {:ns 'demo.worker :phase :wait}))
             :wake (-> (kernel/step-complete :woke)
                       (kernel/at-site {:ns 'demo.worker :phase :finish}))
             :finish (-> (kernel/step-complete :done)
                         (kernel/with-world (update world :seen conj task))
                         (kernel/at-site {:ns 'demo.fast :phase :finish}))))})

(defn- caught-data [f]
  (try (f) nil (catch :default error (ex-data error))))

;; --- Separate-process read-only Session attachment ---

(def ^:private remote-read-frame-var
  (resolve 'jolt.sim.viewer.remote-session/read-frame*))

(def ^:private remote-step-frame-var
  (resolve 'jolt.sim.viewer.remote-session/step-frame*))

(def ^:private remote-reconcile-step-var
  (resolve 'jolt.sim.viewer.remote-session/reconcile-step*))

(defn- remote-source []
  {:port 19876
   :capability-token token
   :session-instance-id session-instance-id
   :timeout-ms 250})

(defn- http-response
  ([body] (http-response 200 session-instance-id "1" body []))
  ([status instance body extra-headers]
   (http-response status instance "1" body extra-headers))
  ([status instance next-cursor body extra-headers]
   (let [body-bytes (.getBytes ^String body "UTF-8")]
     (.getBytes
      (str "HTTP/1.1 " status " Test\r\n"
           "Content-Type: application/edn\r\n"
           "Content-Length: " (alength body-bytes) "\r\n"
           "X-Jolt-Sim-Journal-Next-Cursor: " next-cursor "\r\n"
           (when instance
             (str "X-Jolt-Sim-Session-Instance: " instance "\r\n"))
           (apply str (map (fn [[name value]]
                             (str name ": " value "\r\n"))
                           extra-headers))
           "\r\n"
           body)
      "UTF-8"))))

(defn- remote-ops [response read-chunks calls]
  (let [offset (atom 0)
        chunks (atom (vec read-chunks))]
    {:now (fn [] 1000000)
     :connect (fn [port deadline]
                (swap! calls conj [:connect port deadline])
                ::connection)
     :send (fn [connection payload deadline]
             (swap! calls conj
                    [:send connection (String. payload "UTF-8") deadline]))
     :receive
     (fn [connection destination destination-offset length deadline]
       (swap! calls conj [:receive connection destination-offset length deadline])
       (if (= @offset (alength ^bytes response))
         nil
         (let [requested (or (first @chunks) length)
               _ (when (seq @chunks) (swap! chunks subvec 1))
               amount (min length requested
                           (- (alength ^bytes response) @offset))]
           (dotimes [index amount]
             (aset destination (+ destination-offset index)
                   (aget ^bytes response (+ @offset index))))
           (swap! offset + amount)
           amount)))
     :close (fn [connection]
              (swap! calls conj [:close connection]) true)}))

(defn- remote-read [source response chunks calls cursor]
  (@remote-read-frame-var
   (remote-ops response chunks calls)
   (assoc (remote-session/validate-source! source) :max-frame-bytes 4096)
   cursor))

(defn- remote-step [ops branch cursor]
  (@remote-step-frame-var
   ops
   (assoc (remote-session/validate-source! (remote-source))
          :max-frame-bytes 4096)
   branch cursor))

(defn- step-http-response [status body]
  (http-response status session-instance-id "0"
                 (trace/canonical-edn body) []))

(def committed-remote-receipt
  {:version 1
   :status :committed
   :committed? true
   :ack {:branch {:revision 0 :action [:run 2]}
         :revision 1}
   :frame-status :available})

(deftest remote-session-source-is-closed-and-header-safe
  (is (= (remote-source)
         (remote-session/validate-source! (remote-source))))
  (doseq [[source reason]
          [[(assoc (remote-source) :host "example.com") :unknown-keys]
           [(assoc (remote-source) :port 0) :invalid-port]
           [(assoc (remote-source) :capability-token
                   (str token "\r\nX-Injected: yes"))
            :invalid-capability-token]
           [(assoc (remote-source) :capability-token
                   (str token (char 127)))
            :invalid-capability-token]
           [(assoc (remote-source) :capability-token (str " " token))
            :invalid-capability-token]
           [(assoc (remote-source) :capability-token (str token " "))
            :invalid-capability-token]
           [(assoc (remote-source) :session-instance-id "short")
            :invalid-session-instance-id]
           [(assoc (remote-source) :timeout-ms 0) :invalid-timeout-ms]
           [(assoc (remote-source) :timeout-ms 60001) :invalid-timeout-ms]
           [(assoc (remote-source) :capability-token
                   (apply str (repeat 257 \x)))
            :invalid-capability-token]]]
    (is (= reason (:reason (caught-data
                            #(remote-session/validate-source! source))))))
  (doseq [cursor [-1 (inc Long/MAX_VALUE)]]
    (is (= :invalid-cursor
           (:reason
            (caught-data
             #(@remote-read-frame-var
               (remote-ops (byte-array 0) [] (atom []))
               (assoc (remote-session/validate-source! (remote-source))
                      :max-frame-bytes 4096)
               cursor)))))))

(deftest remote-session-read-uses-one-deadline-and-exact-request
  (let [calls (atom [])
        value (remote-read (remote-source)
                           (http-response
                            200 session-instance-id "18"
                            "{:fixture :remote, :journal {:cursor 17, :next-cursor 18}}"
                            [])
                           [1 2 7 11 4096]
                           calls 17)
        deadlines (keep last (filter #(contains? #{:connect :send :receive}
                                                   (first %))
                                     @calls))
        request-text (nth (first (filter #(= :send (first %)) @calls)) 2)]
    (is (= {:fixture :remote
            :journal {:cursor 17 :next-cursor 18}}
           value))
    (is (seq deadlines))
    (is (apply = deadlines)
        "connect, send, and every receive share one absolute deadline")
    (is (string/includes? request-text
                          "GET /api/session-frame HTTP/1.1\r\n"))
    (is (string/includes? request-text
                          "Accept: application/edn\r\n"))
    (is (string/includes? request-text
                          (str "X-Jolt-Sim-Capability: " token "\r\n")))
    (is (string/includes? request-text
                          "X-Jolt-Sim-Journal-Cursor: 17\r\n"))
    (is (string/includes?
         request-text
         (str "X-Jolt-Sim-Session-Instance: " session-instance-id "\r\n")))
    (is (= [:close ::connection] (last @calls)))))

(deftest remote-session-http-framing-fails-closed
  (let [body "{:ok true, :journal {:cursor 0, :next-cursor 1}}"
        body-bytes (.getBytes ^String body "UTF-8")
        oversized-head
        (.getBytes
         (str "HTTP/1.1 200 OK\r\n"
              "Content-Type: application/edn\r\n"
              "Content-Length: 4097\r\n"
              "X-Jolt-Sim-Journal-Next-Cursor: 1\r\n"
              "X-Jolt-Sim-Session-Instance: " session-instance-id
              "\r\n\r\n")
         "UTF-8")
        oversized-unclosed-head
        (.getBytes
         (str "HTTP/1.1 200 OK\r\nX-Fill: "
              (apply str (repeat 17000 \x)))
         "UTF-8")
        malformed-utf8-body
        (byte-array (map unchecked-byte [0x22 0xC3 0x28 0x22]))
        malformed-utf8-response
        (concat-byte-arrays
         [(.getBytes
           (str "HTTP/1.1 200 OK\r\n"
                "Content-Type: application/edn\r\n"
                "Content-Length: " (alength malformed-utf8-body) "\r\n"
                "X-Jolt-Sim-Journal-Next-Cursor: 1\r\n"
                "X-Jolt-Sim-Session-Instance: " session-instance-id
                "\r\n\r\n")
           "UTF-8")
          malformed-utf8-body])
        cases
        [[:duplicate-header
          (http-response 200 session-instance-id body
                         [["Content-Length" (str (alength body-bytes))]])]
         [:transfer-encoding-forbidden
          (http-response 200 session-instance-id body
                         [["Transfer-Encoding" "chunked"]])]
         [:truncated-body
          (java.util.Arrays/copyOfRange (http-response body) 0
                                        (dec (alength (http-response body))))]
         [:surplus-body
          (.getBytes
           (str (String. (http-response body) "UTF-8") "x") "UTF-8")]
         [:truncated-headers (.getBytes "HTTP/1.1 200 OK\r\n" "UTF-8")]
         [:headers-too-large oversized-unclosed-head]
         [:body-too-large oversized-head]
         [:empty-body
          (http-response 200 session-instance-id "0" "" [])]
         [:invalid-edn
          (http-response 200 session-instance-id "1" "{" [])]
         [:invalid-utf8 malformed-utf8-response]
         [:trailing-body
          (http-response
           200 session-instance-id "1"
           "{:journal {:cursor 0, :next-cursor 1}} :extra" [])]
         [:malformed-header
          (.getBytes
           (str "HTTP/1.1 200 OK\r\nBad Header: x\r\n"
                "Content-Length: 0\r\n\r\n") "UTF-8")]]]
    (doseq [[reason response] cases]
      (let [calls (atom [])
            data (caught-data
                  #(remote-read (remote-source) response [4096] calls 0))]
        (is (= :jolt.sim.viewer.remote-session/invalid-response (:type data)))
        (is (= reason (:reason data)))
        (is (= [:close ::connection] (last @calls))
            (str "connection closes after " reason))))))

(deftest remote-session-validates-media-and-next-cursor-contract
  (let [body "{:journal {:cursor 0, :next-cursor 1}}"
        missing-media
        (.getBytes
         (str "HTTP/1.1 200 OK\r\n"
              "Content-Length: " (count body) "\r\n"
              "X-Jolt-Sim-Journal-Next-Cursor: 1\r\n"
              "X-Jolt-Sim-Session-Instance: " session-instance-id "\r\n\r\n"
              body) "UTF-8")
        cases
        [[:invalid-content-type missing-media]
         [:invalid-next-cursor
          (http-response 200 session-instance-id "01" body [])]
         [:next-cursor-mismatch
          (http-response 200 session-instance-id "2" body [])]
         [:next-cursor-mismatch
          (http-response 200 session-instance-id "1"
                         "{:journal {:cursor 9, :next-cursor 1}}" [])]]]
    (doseq [[reason response] cases]
      (is (= reason
             (:reason
              (caught-data
               #(remote-read (remote-source) response [4096] (atom []) 0))))))))

(deftest remote-session-authority-failure-is-not-a-false-restart
  (let [data
        (caught-data
         #(remote-read (remote-source)
                       (http-response 403 nil "{:error :forbidden}" [])
                       [4096] (atom []) 0))]
    (is (= remote-session/source-unavailable (:type data)))
    (is (= 403 (:status data)))))

(deftest remote-session-cleanup-cannot-replace-the-primary-failure
  (let [response (.getBytes "HTTP/1.1 200 OK\r\n" "UTF-8")
        close-error (ex-info "close failed" {:type ::close-failed})
        ops (assoc (remote-ops response [4096] (atom []))
                   :close (fn [_] (throw close-error)))
        data (caught-data
              #(@remote-read-frame-var
                ops
                (assoc (remote-session/validate-source! (remote-source))
                       :max-frame-bytes 4096)
                0))]
    (is (= remote-session/invalid-response (:type data)))
    (is (= :truncated-headers (:reason data)))
    (is (= ::close-failed
           (get-in data [:remote-session/cleanup-error :type])))))

(deftest remote-session-surfaces-a-close-only-failure
  (let [response
        (http-response
         200 session-instance-id "1"
         "{:journal {:cursor 0, :next-cursor 1}}" [])
        ops (assoc (remote-ops response [4096] (atom []))
                   :close (fn [_]
                            (throw (ex-info "close failed"
                                            {:type ::close-failed}))))
        data (caught-data
              #(@remote-read-frame-var
                ops
                (assoc (remote-session/validate-source! (remote-source))
                       :max-frame-bytes 4096)
                0))]
    (is (= ::close-failed (:type data)))))

(deftest remote-session-pins-the-producer-epoch-without-adoption
  (doseq [observed [nil "ripple-session-instance-NEW2"]]
    (let [calls (atom [])
          data (caught-data
                #(remote-read (remote-source)
                              (http-response 200 observed "{:ignored true}" [])
                              [4096] calls 3))]
      (is (= remote-session/source-restarted (:type data)))
      (is (= session-instance-id (:expected-session-instance-id data)))
      (is (= observed (:observed-session-instance-id data)))
      (is (= [:close ::connection] (last @calls)))))
  (let [handler
        (viewer/make-handler
         (assoc (config) :max-document-bytes (* 1024 1024))
         (assoc (services (atom []) (atom []) {:status :completed})
                :read-session-frame
                (fn [_]
                  (throw (ex-info "changed"
                                  {:type remote-session/source-restarted})))))
        response (handler (get-request "/api/session-frame"))]
    (is (= 409 (:status response)))
    (is (string/includes? (:body response) ":session-source-restarted"))
    (is (not (string/includes? (:body response) "changed")))))

(deftest remote-session-step-sends-one-exact-pinned-command
  (let [calls (atom [])
        branch {:revision 0 :action [:run 2]}
        result (remote-step
                (remote-ops (step-http-response 200 committed-remote-receipt)
                            [1 3 7 4096] calls)
                branch 0)
        request-text (nth (first (filter #(= :send (first %)) @calls)) 2)
        deadlines (keep last (filter #(contains? #{:connect :send :receive}
                                                   (first %)) @calls))]
    (is (= :committed (:status result)))
    (is (= {:branch branch :revision 1} (:ack result)))
    (is (= {:jolt.sim.session-view/type :frame} (:frame result)))
    (is (apply = deadlines))
    (is (= 1 (count (filter #(= :connect (first %)) @calls))))
    (is (= 1 (count (filter #(= :send (first %)) @calls))))
    (is (string/includes? request-text
                          "POST /api/session-step HTTP/1.1\r\n"))
    (is (string/includes? request-text
                          (str "X-Jolt-Sim-Session-Instance: "
                               session-instance-id "\r\n")))
    (is (string/ends-with?
         request-text
         "\r\n\r\n{\"version\":1,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"))))

(deftest remote-session-step-preserves-a-recognized-receipt-on-close-failure
  (let [branch {:revision 0 :action [:run 2]}
        ops (assoc
             (remote-ops (step-http-response 200 committed-remote-receipt)
                         [4096] (atom []))
             :close (fn [_]
                      (throw (ex-info "close failed" {:type ::close-failed}))))
        result (remote-step ops branch 0)]
    (is (= :committed (:status result)))
    (is (true? (:committed? result)))
    (is (= {:branch branch :revision 1} (:ack result)))))

(deftest remote-session-step-preserves-custom-post-commit-frame-failure
  (let [branch {:revision 0 :action [:run 2]}
        receipt (assoc committed-remote-receipt
                       :frame-status :unavailable
                       :frame-error {:type :app/preview-failed
                                     :phase :post-commit})
        result (remote-step
                (remote-ops (step-http-response 200 receipt)
                            [4096] (atom []))
                branch 0)]
    (is (= :committed (:status result)))
    (is (true? (:committed? result)))
    (is (nil? (:frame result)))
    (is (= {:type :app/preview-failed :phase :post-commit}
           (:frame-error result)))))

(deftest remote-session-step-ambiguity-is-typed-and-never-retried
  (let [branch {:revision 0 :action [:run 2]}
        malformed (.getBytes "HTTP/1.1 200 OK\r\n" "UTF-8")
        server-error (step-http-response 500 {:error :session-step-error})
        cases
        [[:connect
          (assoc (remote-ops (byte-array 0) [] (atom []))
                 :connect (fn [& _]
                            (throw (ex-info "connect failed" {}))))]
         [:exchange
          (assoc (remote-ops (byte-array 0) [] (atom []))
                 :send (fn [& _]
                         (throw (ex-info "send failed" {}))))]
         [:exchange (remote-ops malformed [4096] (atom []))]
         [:exchange (remote-ops server-error [4096] (atom []))]]]
    (doseq [[phase ops] cases]
      (let [data (caught-data #(remote-step ops branch 0))]
        (is (= remote-session/step-outcome-unknown (:type data)))
        (is (= phase (:phase data)))))))

(deftest remote-session-step-rejects-a-replacement-before-receipt-adoption
  (let [branch {:revision 0 :action [:run 2]}
        replacement
        (http-response 409 "ripple-session-instance-NEW2" "0"
                       "{:error :session-instance-mismatch}" [])
        data (caught-data
              #(remote-step (remote-ops replacement [4096] (atom []))
                            branch 0))]
    (is (= remote-session/source-restarted (:type data)))
    (is (= session-instance-id (:expected-session-instance-id data)))
    (is (= "ripple-session-instance-NEW2"
           (:observed-session-instance-id data)))))

(deftest remote-session-step-recognizes-closed-pre-service-rejections
  (let [branch {:revision 0 :action [:run 2]}
        cases [[403 nil :forbidden]
               [400 session-instance-id :invalid-session-step]
               [404 session-instance-id :session-step-unavailable]
               [409 session-instance-id :session-step-rejected]
               [413 session-instance-id :request-too-large]
               [415 session-instance-id :expected-application-json]
               [429 session-instance-id :session-step-busy]
               [429 session-instance-id :viewer-busy]]]
    (doseq [[status instance reason] cases]
      (let [response (http-response status instance "0"
                                    (trace/canonical-edn {:error reason}) [])
            data (caught-data
                  #(remote-step (remote-ops response [4096] (atom []))
                                branch 0))]
        (is (= remote-session/source-unavailable (:type data)))
        (is (= status (:status data)))
        (is (= reason (:reason data)))))))

(deftest remote-session-step-unknown-is-a-typed-outer-response
  (let [text "{\"version\":1,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"
        request {:request-method :post
                 :uri "/api/session-step"
                 :headers {"content-type" "application/json"
                           "content-length"
                           (str (alength (.getBytes ^String text "UTF-8")))
                           "x-jolt-sim-capability" token}
                 :body (body [(.getBytes ^String text "UTF-8")])}
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :step-session-frame!
                (fn [& _]
                  (throw (ex-info "inner transport secret"
                                  {:type remote-session/step-outcome-unknown
                                   :phase :exchange
                                   :cause-type ::receive-failed
                                   :secret "must-not-cross"})))))
        response (handler request)]
    (is (= 503 (:status response)))
    (is (string/includes? (:body response)
                          ":session-step-outcome-unknown"))
    (is (string/includes? (:body response) ":phase :exchange"))
    (is (not (string/includes? (:body response) "inner transport secret")))
    (is (not (string/includes? (:body response) "must-not-cross")))))

(deftest remote-session-definite-rejection-is-a-secret-free-json-error
  (let [text "{\"version\":1,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"
        request {:request-method :post
                 :uri "/api/session-step"
                 :headers {"content-type" "application/json"
                           "accept" "application/json"
                           "content-length"
                           (str (alength (.getBytes ^String text "UTF-8")))
                           "x-jolt-sim-capability" token}
                 :body (body [(.getBytes ^String text "UTF-8")])}
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :step-session-frame!
                (fn [& _]
                  (throw (ex-info "inner authority secret"
                                  {:type remote-session/source-unavailable
                                   :status 403
                                   :reason :forbidden
                                   :secret "must-not-cross"})))))
        response (handler request)]
    (is (= 403 (:status response)))
    (is (= {"version" 1 "outcome" "error" "committed" false
            "error" "forbidden"}
           (json/read-str (:body response))))
    (is (not (string/includes? (:body response) "must-not-cross")))))

(deftest remote-session-step-reconciliation-is-explicit-and-read-only
  (let [branch {:revision 0 :action [:run 2]}
        start {:seq 0 :command :start}
        exact {:seq 1 :command :step :branch branch}
        other {:seq 1 :command :step
               :branch {:revision 0 :action [:run 0]}}
        frame (fn [entries]
                {:revision (dec (count entries))
                 :journal {:cursor 0
                           :next-cursor (count entries)
                           :count (count entries)
                           :page-size (count entries)
                           :remaining? false
                           :entries entries}})
        reconcile (fn [entries]
                    (@remote-reconcile-step-var
                     (fn [_] (frame entries)) branch 0))]
    (is (= :committed (:status (reconcile [start exact]))))
    (is (= {:seq 1 :command :step :branch branch}
           (:observed (reconcile [start exact]))))
    (is (= :different (:status (reconcile [start other]))))
    (is (= (:branch other)
           (get-in (reconcile [start other]) [:observed :branch])))
    (is (= :missing (:status (reconcile [start]))))))

(deftest remote-session-step-reconciliation-pages-from-the-original-cursor
  (let [branch {:revision 0 :action [:run 2]}
        cursors (atom [])
        read-frame
        (fn [cursor]
          (swap! cursors conj cursor)
          (case cursor
            0 {:revision 1
               :journal {:cursor 0 :next-cursor 1 :count 2 :page-size 1
                         :remaining? true
                         :entries [{:seq 0 :command :start}]}}
            1 {:revision 1
               :journal {:cursor 1 :next-cursor 2 :count 2 :page-size 1
                         :remaining? false
                         :entries [{:seq 1 :command :step :branch branch}]}}))
        result (@remote-reconcile-step-var read-frame branch 0)]
    (is (= :committed (:status result)))
    (is (= [0 1] @cursors))))

(deftest remote-session-step-reconciliation-rejects-regressing-pages
  (let [branch {:revision 1 :action [:run 0]}
        read-frame
        (fn [cursor]
          (case cursor
            0 {:revision 1
               :journal {:cursor 0 :next-cursor 1 :count 2 :page-size 1
                         :remaining? true
                         :entries [{:seq 0 :command :start}]}}
            1 {:revision 0
               :journal {:cursor 1 :next-cursor 1 :count 1 :page-size 0
                         :remaining? false :entries []}}))
        data (caught-data
              #(@remote-reconcile-step-var read-frame branch 0))]
    (is (= remote-session/invalid-response (:type data)))
    (is (= :invalid-reconciliation-journal (:reason data)))))

(deftest remote-session-step-reconciliation-requires-semantic-journal-entries
  (let [branch {:revision 0 :action [:run 2]}
        malformed
        [{:seq 1 :command :mystery :branch branch}
         {:seq 1 :command :step
          :branch {:revision 1 :action [:run 0]}}
         {:seq 1 :command :step :branch {:revision 0 :action [:run -1]}}]]
    (doseq [entry malformed]
      (let [frame {:revision 1
                   :journal {:cursor 0 :next-cursor 2 :count 2 :page-size 2
                             :remaining? false
                             :entries [{:seq 0 :command :start} entry]}}
            data (caught-data
                  #(@remote-reconcile-step-var (fn [_] frame) branch 0))]
        (is (= remote-session/invalid-response (:type data)))
        (is (= :invalid-reconciliation-journal (:reason data)))))
    (let [frame {:revision 1
                 :journal {:cursor 0 :next-cursor 2 :count 2 :page-size 2
                           :remaining? false
                           :entries [{:seq 0 :command :mystery}
                                     {:seq 1 :command :step
                                      :branch branch}]}}
          data (caught-data
                #(@remote-reconcile-step-var (fn [_] frame) branch 0))]
      (is (= remote-session/invalid-response (:type data)))
      (is (= :invalid-reconciliation-journal (:reason data))))))

(deftest remote-session-step-reconciliation-has-a-hard-page-budget
  (let [branch {:revision 64 :action [:run 0]}
        calls (atom 0)
        read-frame
        (fn [cursor]
          (swap! calls inc)
          {:revision 65
           :journal {:cursor cursor
                     :next-cursor (inc cursor)
                     :count 66
                     :page-size 1
                     :remaining? true
                     :entries [(if (zero? cursor)
                                 {:seq 0 :command :start}
                                 {:seq cursor :command :step
                                  :branch {:revision (dec cursor)
                                           :action [:run 0]}})]}})
        data (caught-data
              #(@remote-reconcile-step-var read-frame branch 0))]
    (is (= remote-session/reconciliation-limit-exceeded (:type data)))
    (is (= 64 (:maximum-pages data)))
    (is (= 64 @calls))))

(defn- run-to-terminal [s]
  (doseq [branch [{:revision 0 :action [:run 2]}
                  {:revision 1 :action [:run 0]}
                  {:revision 2 :action [:advance 5]}
                  {:revision 3 :action [:run 0]}]]
    (session/step! s branch)))

(defn- scripted-ops
  "Builds an ops map whose reads pop one state per call from `states`, so a
  test can script a concurrent step landing between the frame's reads. Each
  state is `{:revision R :journal-count (inc R)}`."
  [states]
  (let [remaining (atom (vec states))]
    (letfn [(pop-state []
              (let [state (first @remaining)]
                (swap! remaining #(subvec % 1))
                state))]
      {:snapshot
       (fn []
         (let [{:keys [revision journal-count]} (pop-state)]
           {:revision revision
            :status :runnable
            :projection nil
            :branches [{:revision revision :action [:run 0]}]
            :journal {:count journal-count}}))
       :previews
       (fn []
         (let [{:keys [revision]} (pop-state)]
           [{:branch {:revision revision :action [:run 0]}
             :site nil :status :runnable :projection nil :events []}]))
       :journal
       (fn []
         (let [{:keys [journal-count]} (pop-state)]
           (mapv (fn [i] {:seq i :command (if (zero? i) :start :step)})
                 (range journal-count))))
       :step! (fn [_] (throw (ex-info "unused" {:type ::unused})))})))

(deftest session-frame-endpoint-is-optional-authorized-and-read-only
  (let [s (session/start (session-sim-config))
        viewer-config (assoc (config) :max-document-bytes (* 1024 1024))
        expected (viewer-session/read-frame s 0)
        before-snapshot (session/snapshot s)
        before-journal (session/journal s)
        read-calls (atom [])
        handler
        (viewer/make-handler
         viewer-config
         (assoc (services (atom []) (atom []) {:status :completed})
                :read-session-frame
                (fn [cursor]
                  (swap! read-calls conj cursor)
                  (viewer-session/read-frame s cursor))))
        unavailable
        (viewer/make-handler
         viewer-config
         (services (atom []) (atom []) {:status :completed}))
        forbidden (handler (get-request "/api/session-frame" "wrong"))
        missing (unavailable (get-request "/api/session-frame"))
        response (handler (get-request "/api/session-frame"))]
    (is (= 403 (:status forbidden)))
    (is (= 404 (:status missing)))
    (is (string/includes? (:body missing) ":session-unavailable"))
    (is (= [0] @read-calls)
        "unauthorized and unavailable requests never invoke a frame reader")
    (is (= 200 (:status response)))
    (is (= "1" (get-in response
                         [:headers "X-Jolt-Sim-Journal-Next-Cursor"])))
    (is (= (trace/canonical-edn
            (update expected :journal assoc
                    :page-size 1 :remaining? false))
           (:body response)))
    (is (= (:branches expected) (mapv :branch (:previews expected))))
    (is (= before-snapshot (session/snapshot s)))
    (is (= before-journal (session/journal s))
        "reading through HTTP neither steps nor appends to the Session")))

(deftest session-frame-publishes-the-configured-instance-only-after-authority
  (let [s (session/start (session-sim-config))
        calls (atom [])
        viewer-config (assoc (config)
                             :max-document-bytes (* 1024 1024)
                             :session-instance-id session-instance-id)
        base-services (services (atom []) (atom []) {:status :completed})
        handler
        (viewer/make-handler
         viewer-config
         (assoc base-services
                :read-session-frame
                (fn [cursor]
                  (swap! calls conj cursor)
                  (viewer-session/read-frame s cursor))))
        unavailable (viewer/make-handler viewer-config base-services)
        forbidden (handler (get-request "/api/session-frame" "wrong"))
        available (handler (get-request "/api/session-frame"))
        missing (unavailable (get-request "/api/session-frame"))]
    (is (= 403 (:status forbidden)))
    (is (nil? (get-in forbidden
                      [:headers "X-Jolt-Sim-Session-Instance"]))
        "an unauthorized response has no producer-instance oracle")
    (is (= 200 (:status available)))
    (is (= session-instance-id
           (get-in available
                   [:headers "X-Jolt-Sim-Session-Instance"])))
    (is (= 404 (:status missing)))
    (is (= session-instance-id
           (get-in missing
                   [:headers "X-Jolt-Sim-Session-Instance"]))
        "every authorized frame response identifies the producer")
    (is (= [0] @calls)
        "authorization and unavailable-service handling do not call the reader")))

(deftest session-frame-json-is-explicit-closed-and-preserves-the-edn-frame
  (let [s (session/start (session-sim-config))
        base-services
        (assoc (services (atom []) (atom []) {:status :completed})
               :read-session-frame #(viewer-session/read-frame s %))
        viewer-config (assoc (config) :max-document-bytes (* 1024 1024))
        read-only (viewer/make-handler viewer-config base-services)
        steppable (viewer/make-handler
                   viewer-config
                   (assoc base-services :step-session-frame! (fn [& _] nil)))
        json-request (assoc-in (get-request "/api/session-frame")
                               [:headers "accept"]
                               "text/plain, application/json; q=1")
        read-only-response (read-only json-request)
        steppable-response (steppable json-request)
        wildcard-response
        (read-only (assoc-in (get-request "/api/session-frame")
                             [:headers "accept"] "*/*"))
        refused-json-response
        (read-only (assoc-in (get-request "/api/session-frame")
                             [:headers "accept"] "application/json;q=0"))
        invalid-quality-response
        (read-only (assoc-in (get-request "/api/session-frame")
                             [:headers "accept"] "application/json;q=bogus"))
        wire (json/read-str (:body read-only-response))]
    (is (= 200 (:status read-only-response)))
    (is (= "application/json; charset=utf-8"
           (get-in read-only-response [:headers "Content-Type"])))
    (is (= #{"version" "revision" "nextCursor" "stepEnabled"
             "frameEdn" "choices"}
           (set (keys wire))))
    (is (= 1 (get wire "version")))
    (is (= "0" (get wire "revision")))
    (is (= "1" (get wire "nextCursor")))
    (is (false? (get wire "stepEnabled")))
    (is (= [{"revision" "0" "kind" "run" "value" "0" "label" "run 0"}
            {"revision" "0" "kind" "run" "value" "2" "label" "run 2"}]
           (get wire "choices")))
    (is (= (edn/read-string (get wire "frameEdn"))
           (edn/read-string (:body wildcard-response))))
    (is (= "application/edn; charset=utf-8"
           (get-in wildcard-response [:headers "Content-Type"])))
    (is (= "application/edn; charset=utf-8"
           (get-in refused-json-response [:headers "Content-Type"])))
    (is (= "application/edn; charset=utf-8"
           (get-in invalid-quality-response [:headers "Content-Type"])))
    (is (true? (get (json/read-str (:body steppable-response))
                    "stepEnabled")))
    (is (= "1" (get-in read-only-response
                         [:headers "X-Jolt-Sim-Journal-Next-Cursor"])))))

(deftest session-frame-json-rejects-defective-trusted-branch-projections
  (let [s (session/start (session-sim-config))
        response
        ((viewer/make-handler
          (assoc (config) :max-document-bytes (* 1024 1024))
          (assoc (services (atom []) (atom []) {:status :completed})
                 :read-session-frame
                 (fn [cursor]
                   (update (viewer-session/read-frame s cursor)
                           :branches
                           assoc 0
                           {:revision 0 :action [:run 0] :secret "nope"}))))
         (assoc-in (get-request "/api/session-frame")
                   [:headers "accept"] "application/json"))]
    (is (= 500 (:status response)))
    (is (= "application/edn; charset=utf-8"
           (get-in response [:headers "Content-Type"])))
    (is (string/includes? (:body response) ":session-frame-unavailable"))
    (is (not (string/includes? (:body response) "nope")))))

(deftest session-frame-json-envelope-is-counted-against-the-response-limit
  (let [s (session/start (session-sim-config))
        service-map
        (assoc (services (atom []) (atom []) {:status :completed})
               :read-session-frame #(viewer-session/read-frame s %))
        wide-handler
        (viewer/make-handler
         (assoc (config) :max-document-bytes (* 1024 1024))
         service-map)
        edn-response (wide-handler (get-request "/api/session-frame"))
        edn-bytes (alength (.getBytes ^String (:body edn-response) "UTF-8"))
        tight-handler
        (viewer/make-handler
         (assoc (config) :max-document-bytes edn-bytes)
         service-map)
        tight-edn (tight-handler (get-request "/api/session-frame"))
        tight-json
        (tight-handler
         (assoc-in (get-request "/api/session-frame")
                   [:headers "accept"] "application/json"))]
    (is (= 200 (:status tight-edn))
        "the unchanged EDN representation still fits its exact byte limit")
    (is (= 413 (:status tight-json))
        "duplicated JSON metadata and frameEdn are measured, not appended after the bound")
    (is (string/includes? (:body tight-json) ":session-frame-too-large"))))

(deftest session-frame-endpoint-validates-and-forwards-the-journal-cursor
  (let [cursors (atom [])
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :read-session-frame
                (fn [cursor]
                  (swap! cursors conj cursor)
                  {:jolt.sim.session-view/type :frame
                   :revision 3 :status nil :projection {}
                   :branches [] :previews []
                   :journal {:cursor cursor :next-cursor 7
                             :count 7 :page-size 1 :remaining? false
                             :entries [{:seq 6}]}})))
        cursor-request (assoc-in (get-request "/api/session-frame")
                                 [:headers "x-jolt-sim-journal-cursor"] "6")
        malformed-request (assoc-in (get-request "/api/session-frame")
                                    [:headers "x-jolt-sim-journal-cursor"] "-1")
        overflow-request (assoc-in (get-request "/api/session-frame")
                                   [:headers "x-jolt-sim-journal-cursor"]
                                   "999999999999999999999999999999")
        response (handler cursor-request)
        malformed (handler malformed-request)
        overflow (handler overflow-request)]
    (is (= 200 (:status response)))
    (is (= [6] @cursors))
    (is (= "7" (get-in response
                         [:headers "X-Jolt-Sim-Journal-Next-Cursor"])))
    (is (= 400 (:status malformed)))
    (is (= 400 (:status overflow)))
    (is (string/includes? (:body malformed) ":invalid-session-cursor"))
    (is (string/includes? (:body overflow) ":out-of-range"))
    (is (= [6] @cursors)
        "malformed cursors fail before the trusted reader is called")))

(deftest session-frame-endpoint-translates-adapter-failures-without-details
  (doseq [[type expected-status expected-error]
          [[:jolt.sim.session-view/invalid-cursor
            400 :invalid-session-cursor]
           [:jolt.sim.session-view/coherence-failed
            409 :session-frame-incoherent]]]
    (let [handler
          (viewer/make-handler
           (config)
           (assoc (services (atom []) (atom []) {:status :completed})
                  :read-session-frame
                  (fn [_]
                    (throw
                     (ex-info "private detail"
                              {:type type :reason :ahead-of-journal
                               :cursor 9 :journal-count 1
                               :attempts 8 :max-attempts 8
                               :secret "must-not-cross"})))))
          response (handler (get-request "/api/session-frame"))]
      (is (= expected-status (:status response)))
      (is (string/includes? (:body response) (str expected-error)))
      (is (not (string/includes? (:body response) "private detail")))
      (is (not (string/includes? (:body response) "must-not-cross"))))))

(deftest session-frame-endpoint-bounds-journal-pages-and-total-bytes
  (let [entries (mapv (fn [seq] {:seq seq :payload "bounded"}) (range 300))
        services-map
        (assoc (services (atom []) (atom []) {:status :completed})
               :read-session-frame
               (fn [cursor]
                 (let [next-cursor (min 300 (+ cursor 256))]
                   {:jolt.sim.session-view/type :frame
                    :revision 0 :status nil :projection {}
                    :branches [] :previews []
                    :journal {:cursor cursor :next-cursor next-cursor
                              :count 300
                              :page-size (- next-cursor cursor)
                              :remaining? (< next-cursor 300)
                              :entries (subvec entries cursor next-cursor)}})))
        response ((viewer/make-handler
                   (assoc (config) :max-document-bytes (* 1024 1024))
                   services-map)
                  (get-request "/api/session-frame"))
        too-small ((viewer/make-handler
                    (assoc (config) :max-document-bytes 64)
                    services-map)
                   (get-request "/api/session-frame"))]
    (is (= 200 (:status response)))
    (is (= "256" (get-in response
                           [:headers "X-Jolt-Sim-Journal-Next-Cursor"])))
    (is (string/includes? (:body response) ":page-size 256"))
    (is (string/includes? (:body response) ":remaining? true"))
    (is (not (string/includes? (:body response) ":seq 299")))
    (is (= 413 (:status too-small)))
    (is (string/includes? (:body too-small) ":session-frame-too-large"))))

(deftest session-frame-endpoint-scrubs-unexpected-reader-errors
  (let [handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :read-session-frame
                (fn [_]
                  (throw (ex-info "reader secret" {:secret "must-not-cross"})))))
        response (handler (get-request "/api/session-frame"))]
    (is (= 500 (:status response)))
    (is (string/includes? (:body response) ":session-frame-unavailable"))
    (is (not (string/includes? (:body response) "reader secret")))
    (is (not (string/includes? (:body response) "must-not-cross")))))

(deftest session-frame-endpoint-admits-only-one-reader
  (let [entered (promise)
        release (promise)
        reads (atom 0)
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :read-session-frame
                (fn [cursor]
                  (swap! reads inc)
                  (deliver entered true)
                  @release
                  {:jolt.sim.session-view/type :frame
                   :revision 0 :status nil :projection {}
                   :branches [] :previews []
                   :journal {:cursor cursor :next-cursor cursor
                             :count cursor :page-size 0
                             :remaining? false :entries []}})))
        first-response (future (handler (get-request "/api/session-frame")))]
    (try
      (is (= true (deref entered 5000 ::timeout)))
      (let [busy (handler (get-request "/api/session-frame"))]
        (is (= 429 (:status busy)))
        (is (string/includes? (:body busy) ":session-frame-busy"))
        (is (= 1 @reads)))
      (finally
        (deliver release true)))
    (is (= 200 (:status (deref first-response 5000 {:status ::timeout}))))
    (is (= 200 (:status (handler (get-request "/api/session-frame")))))
    (is (= 2 @reads))))

(deftest start-session-installs-only-the-read-frame-capability
  (let [s (session/start (session-sim-config))
        captured (atom nil)
        start-var (resolve 'jolt.sim.viewer/start!)]
    (with-redefs-fn
      {start-var
       (fn [supplied-config supplied-services]
         (reset! captured {:config supplied-config
                           :services supplied-services})
         :fake-server)}
      #(is (= :fake-server (viewer/start-session! (config) s))))
    (is (= (config) (:config @captured)))
    (is (= #{:render-trace :render-case-outcome :replay-document
             :read-session-frame}
           (set (keys (:services @captured)))))
    (is (nil? (get-in @captured [:services :step-session-frame])))
    (is (nil? (get-in @captured [:services :step-session-frame!]))
        "the read-only attachment never installs a mutating capability")
    (is (= (viewer-session/read-frame s 0)
           ((get-in @captured [:services :read-session-frame]) 0)))))

(deftest start-remote-session-derives-its-bound-and-installs-no-command
  (let [captured (atom nil)
        start-var (resolve 'jolt.sim.viewer/start!)
        reader-var (resolve 'jolt.sim.viewer.remote-session/reader)
        viewer-config (assoc (config) :max-document-bytes 12345)
        source (remote-source)]
    (with-redefs-fn
      {reader-var
       (fn [supplied-source supplied-limit]
         (swap! captured assoc
                :source supplied-source
                :limit supplied-limit)
         (fn [cursor] {:fixture :remote :cursor cursor}))
       start-var
       (fn [supplied-config supplied-services]
         (swap! captured assoc
                :config supplied-config
                :services supplied-services)
         :fake-server)}
      #(is (= :fake-server
              (viewer/start-remote-session! viewer-config source))))
    (is (= source (:source @captured)))
    (is (= 12345 (:limit @captured))
        "the validated outer response cap is the remote response cap")
    (is (= (assoc viewer-config :run-presets []) (:config @captured))
        "the remote starter passes the normalized viewer config to start!")
    (is (= #{:render-trace :render-case-outcome :replay-document
             :read-session-frame}
           (set (keys (:services @captured)))))
    (is (nil? (get-in @captured [:services :step-session-frame!]))
        "the remote attachment cannot install a command capability")
    (is (= {:fixture :remote :cursor 7}
           ((get-in @captured [:services :read-session-frame]) 7)))))

(deftest start-remote-steppable-session-is-explicit-and-installs-the-attachment
  (let [captured (atom nil)
        start-var (resolve 'jolt.sim.viewer/start!)
        attachment-var (resolve 'jolt.sim.viewer.remote-session/attachment)
        viewer-config (assoc (config) :max-document-bytes 12345)
        source (remote-source)
        attachment {:read-frame (fn [cursor] [:read cursor])
                    :step-frame! (fn [branch cursor] [:step branch cursor])
                    :reconcile-step! (fn [branch cursor]
                                       [:reconcile branch cursor])}]
    (with-redefs-fn
      {attachment-var
       (fn [supplied-source supplied-limit]
         (swap! captured assoc :source supplied-source :limit supplied-limit)
         attachment)
       start-var
       (fn [supplied-config supplied-services]
         (swap! captured assoc :config supplied-config
                :services supplied-services)
         :fake-server)}
      #(is (= :fake-server
              (viewer/start-remote-steppable-session!
               viewer-config source))))
    (is (= source (:source @captured)))
    (is (= 12345 (:limit @captured)))
    (is (= #{:render-trace :render-case-outcome :replay-document
             :read-session-frame :step-session-frame!}
           (set (keys (:services @captured)))))
    (is (= [:read 7]
           ((get-in @captured [:services :read-session-frame]) 7)))
    (is (= [:step {:revision 0 :action [:run 2]} 0]
           ((get-in @captured [:services :step-session-frame!])
            {:revision 0 :action [:run 2]} 0)))
    (is (= [:reconcile {:revision 0 :action [:run 2]} 0]
           ((:reconcile-step! attachment)
            {:revision 0 :action [:run 2]} 0)))))

(deftest session-frame-initial-read-is-coherent-and-closed
  (let [s (session/start (session-sim-config))
        frame (viewer-session/read-frame s 0)]
    (is (= #{:jolt.sim.session-view/type :kind :revision :status :projection
             :branches :previews :journal}
           (set (keys frame))))
    (is (= :frame (get frame :jolt.sim.session-view/type)))
    (is (= :jolt.sim.kind/session-frame (:kind frame)))
    (is (= 0 (:revision frame)))
    (is (nil? (:status frame))
        "Session status is nil while the machine still has enabled actions")
    (is (= [{:revision 0 :action [:run 0]}
            {:revision 0 :action [:run 2]}]
           (:branches frame)))
    (is (= [[:run 0] [:run 2]]
           (mapv #(get-in % [:branch :action]) (:previews frame))))
    (is (= [nil nil] (mapv :status (:previews frame)))
        "preview status reports only terminal machine status, not task state")
    (is (= [{:ns 'demo.worker :phase :wait}
            {:ns 'demo.fast :phase :finish}]
           (mapv #(trace/restore-value (:site %)) (:previews frame))))
    (is (= {:cursor 0 :next-cursor 1 :count 1}
           (select-keys (:journal frame) [:cursor :next-cursor :count])))
    (is (= 1 (count (get-in frame [:journal :entries]))))
    (is (= :start (:command (first (get-in frame [:journal :entries])))))
    (is (= 0 (get-in (trace/restore-value (:projection frame)) [:now])))))

(deftest session-frame-tail-advances-without-duplication
  (let [s (session/start (session-sim-config))
        initial (viewer-session/read-frame s 0)]
    (is (= [0] (mapv :seq (get-in initial [:journal :entries]))))
    (session/step! s {:revision 0 :action [:run 2]})
    (let [advanced (viewer-session/read-frame s 1)]
      (is (= 1 (:revision advanced)))
      (is (= {:cursor 1 :next-cursor 2 :count 2}
             (select-keys (:journal advanced)
                          [:cursor :next-cursor :count])))
      (is (= [1] (mapv :seq (get-in advanced [:journal :entries])))
          "the tail carries only the newly appended entry")
      (is (= :step (:command (first (get-in advanced [:journal :entries])))))
      (is (= {:revision 0 :action [:run 2]}
             (get-in advanced [:journal :entries 0 :branch]))))
    (let [from-start (viewer-session/read-frame s 0)]
      (is (= [0 1] (mapv :seq (get-in from-start [:journal :entries]))))
      (is (= 2 (get-in from-start [:journal :count]))))
    (let [caught-up (viewer-session/read-frame s 2)]
      (is (= [] (get-in caught-up [:journal :entries])))
      (is (= 2 (get-in caught-up [:journal :next-cursor])))
      (is (= 2 (get-in caught-up [:journal :count]))))))

(deftest session-frame-rejects-invalid-cursors-fail-closed
  (let [s (session/start (session-sim-config))]
    (doseq [cursor [-1 "0" :zero 1.5]]
      (let [data (caught-data #(viewer-session/read-frame s cursor))]
        (is (= :jolt.sim.session-view/invalid-cursor (:type data)))
        (is (= :not-a-non-negative-integer (:reason data)))))
    (let [data (caught-data #(viewer-session/read-frame s 5))]
      (is (= :jolt.sim.session-view/invalid-cursor (:type data)))
      (is (= :ahead-of-journal (:reason data)))
      (is (= 5 (:cursor data)))
      (is (= 1 (:journal-count data))))
    (let [before (session/snapshot s)
          data (caught-data
                #(viewer-session/step-frame! s {:revision 0 :action [:run 2]} -1))]
      (is (= :jolt.sim.session-view/invalid-cursor (:type data)))
      (is (= before (session/snapshot s))
          "a rejected cursor never reaches the step command"))))

(deftest session-frame-retries-until-revisions-are-coherent
  (let [s0 {:revision 0 :journal-count 1}
        s1 {:revision 1 :journal-count 2}
        ;; Attempt 1: a concurrent step lands between the first and second
        ;; snapshot reads (S1=0, S2=1). Attempt 2 reads everything at 1.
        ops (scripted-ops (vec (concat (repeat 3 s0) (repeat 5 s1))))
        frame (@read-frame-ops-var ops 0)]
    (is (= 1 (:revision frame)))
    (is (= 2 (get-in frame [:journal :count])))
    (is (= 2 (count (get-in frame [:journal :entries]))))
    (is (= [1] (mapv #(get-in % [:branch :revision]) (:previews frame)))
        "previews are at the coherent revision, never mixed")
    (is (= [{:revision 1 :action [:run 0]}] (:branches frame)))))

(deftest session-frame-fails-closed-when-coherence-cannot-be-obtained
  (let [states (vec (for [attempt (range 8)
                          step (range 4)
                          :let [revision (if (= step 3) (inc attempt) attempt)]]
                      {:revision revision :journal-count (inc revision)}))
        ops (scripted-ops states)
        data (caught-data #(@read-frame-ops-var ops 0))]
    (is (= :jolt.sim.session-view/coherence-failed (:type data)))
    (is (= 8 (:attempts data)))))

(deftest session-step-frame-acknowledges-the-applied-branch
  (let [s (session/start (session-sim-config))
        result (viewer-session/step-frame! s {:revision 0 :action [:run 2]} 1)
        frame (:frame result)]
    (is (= :committed (:status result)))
    (is (true? (:committed? result)))
    (is (nil? (:frame-error result)))
    (is (= 1 (:revision frame)))
    (is (= {:branch {:revision 0 :action [:run 2]}
            :revision 1}
           (:ack result)))
    (is (= [[:run 0]]
           (mapv #(get-in % [:branch :action]) (:previews frame))))
    (is (= 2 (get-in frame [:journal :count])))
    (is (= [1] (mapv :seq (get-in frame [:journal :entries]))))
    (is (= :step (:command (first (get-in frame [:journal :entries])))))))

(deftest session-step-frame-requires-explicit-reconfirmation-when-stale
  (let [s (session/start (session-sim-config))]
    ;; A concurrent REPL step commits first, so the supplied branch is stale.
    (session/step! s {:revision 0 :action [:run 2]})
    (let [stale (viewer-session/step-frame!
                 s {:revision 0 :action [:run 0]} 1)
          refreshed (:frame stale)]
      (is (= :stale (:status stale)))
      (is (false? (:committed? stale)))
      (is (nil? (:ack stale)))
      (is (= :jolt.sim.session/stale-branch
             (get-in stale [:stale :type])))
      (is (= 1 (:revision refreshed)))
      (is (= [{:revision 1 :action [:run 0]}] (:branches refreshed))
          "the still-enabled action is shown but never applied implicitly")
      (is (= 2 (count (session/journal s))))
      (let [confirmed (viewer-session/step-frame!
                       s (first (:branches refreshed))
                       (get-in refreshed [:journal :next-cursor]))]
        (is (= :committed (:status confirmed)))
        (is (true? (:committed? confirmed)))
        (is (= {:branch {:revision 1 :action [:run 0]}
                :revision 2}
               (:ack confirmed)))
        (is (= 3 (count (session/journal s))))))))

(deftest session-step-frame-refreshes-without-commit-when-action-disappears
  (let [s (session/start (session-sim-config))]
    (session/step! s {:revision 0 :action [:run 2]})
    (let [before (session/snapshot s)
          result (viewer-session/step-frame!
                  s {:revision 0 :action [:run 2]} 0)]
      (is (= :stale (:status result)))
      (is (false? (:committed? result)))
      (is (= 0 (get-in result [:stale :expected-revision])))
      (is (= 1 (get-in result [:stale :actual-revision])))
      (is (not-any? #(= [:run 2] (:action %))
                    (get-in result [:frame :branches])))
      (is (= before (session/snapshot s))
          "the disappeared action is never applied and never commits")
      (is (= 2 (count (session/journal s)))))))

(deftest session-step-frame-never-loses-ack-after-post-commit-frame-failure
  (let [snapshot-calls (atom 0)
        step-calls (atom [])
        snapshot-value
        (fn []
          (let [call (swap! snapshot-calls inc)
                revision (cond
                           (= call 1) 0
                           (odd? call) 2
                           :else 1)]
            {:revision revision
             :status nil
             :projection nil
             :branches [{:revision revision :action [:run 0]}]
             :journal {:count (inc revision)}}))
        ops {:snapshot snapshot-value
             :previews (fn []
                         [{:branch {:revision 1 :action [:run 0]}
                           :site nil :status nil :projection nil :events []}])
             :journal (fn [] [{:seq 0 :command :start}
                              {:seq 1 :command :step}])
             :step! (fn [branch]
                      (swap! step-calls conj branch)
                      {:revision 1})}
        result (@step-frame-ops-var ops {:revision 0 :action [:run 0]} 0)]
    (is (= :committed (:status result)))
    (is (true? (:committed? result)))
    (is (= {:branch {:revision 0 :action [:run 0]}
            :revision 1}
           (:ack result)))
    (is (nil? (:frame result)))
    (is (= {:type :jolt.sim.session-view/coherence-failed
            :phase :post-commit
            :attempts 8
            :max-attempts 8}
           (:frame-error result)))
    (is (= [{:revision 0 :action [:run 0]}] @step-calls)
        "the command commits exactly once despite losing the post-step frame")))

(deftest session-frame-and-step-on-a-terminal-session
  (let [s (session/start (session-sim-config))]
    (run-to-terminal s)
    (let [frame (viewer-session/read-frame s 0)]
      (is (= :completed (:status frame)))
      (is (= 4 (:revision frame)))
      (is (= [] (:branches frame)))
      (is (= [] (:previews frame)))
      (is (= 5 (get-in frame [:journal :count])))
      (is (= [0 1 2 3 4] (mapv :seq (get-in frame [:journal :entries])))))
    (let [data (caught-data
                #(viewer-session/step-frame! s {:revision 4 :action [:run 0]} 0))]
      (is (= :jolt.sim.kernel/invalid-machine-action (:type data)))
      (is (= 5 (count (session/journal s)))))
    (let [result (viewer-session/step-frame!
                  s {:revision 0 :action [:run 0]} 0)]
      (is (= :stale (:status result)))
      (is (false? (:committed? result)))
      (is (= 4 (get-in result [:stale :actual-revision])))
      (is (= :completed (get-in result [:frame :status]))))))

;; --- Session step endpoint (POST /api/session-step) ---
;;
;; One bounded exact revision-scoped step over the closed JSON contract. The
;; handler tests use both a real in-process Session (commit, stale retry,
;; disabled actions) and scripted service doubles (gates, frame failure,
;; scrubbing), mirroring the viewer-session adapter tests above.

(defn- step-body
  "Builds the closed JSON step contract text from its decimal strings."
  [cursor revision kind value]
  (str "{\"version\":1,\"cursor\":\"" cursor
       "\",\"branch\":{\"revision\":\"" revision
       "\",\"kind\":\"" kind
       "\",\"value\":\"" value "\"}}"))

(defn- step-request
  "Builds a POST /api/session-step request carrying the JSON step contract."
  ([text] (step-request text token))
  ([text supplied-token] (step-request text supplied-token nil))
  ([text supplied-token instance-id]
   {:request-method :post
    :uri "/api/session-step"
    :headers (cond->
              {"content-type" "application/json"
               "content-length" (str (count (.getBytes ^String text "UTF-8")))
               "x-jolt-sim-capability" supplied-token}
               (some? instance-id)
               (assoc "x-jolt-sim-session-instance" instance-id))
    :body (body [(.getBytes ^String text "UTF-8")])}))

(defn- recording-body
  "A request body that records any read, for proving a rejection precedes
  body consumption."
  [read?]
  (reify http-body/RequestBody
    (body-recv [_]
      (reset! read? true)
      nil)
    (body-bytes [_]
      (reset! read? true)
      (byte-array 0))
    (body-string [_ _]
      (reset! read? true)
      "")))

;; --- Evaluation/document-inspection-only workbench ---
;;
;; Omitting both `:allowed-scenarios` and `:runtime-config` selects an explicit
;; eval-only mode: rendering and an injected `:evaluate-form!` service keep
;; working, while the replay/run routes fail closed as unavailable before any
;; body read or service call. The two keys are an all-or-nothing pair, and a
;; nonempty run catalog is rejected without them.

(defn- eval-only-config []
  (dissoc (config) :allowed-scenarios :runtime-config))

(deftest eval-only-config-is-valid-and-renders-and-evaluates
  (let [cfg (eval-only-config)
        validated (viewer/validate-config! cfg)]
    (is (not (contains? validated :allowed-scenarios)))
    (is (not (contains? validated :runtime-config)))
    (is (= [] (:run-presets validated)))
    (let [render-calls (atom [])
          eval-calls (atom [])
          handler
          (viewer/make-handler
           cfg
           (assoc (services render-calls (atom []) nil)
                  :evaluate-form!
                  (fn [form]
                    (swap! eval-calls conj form)
                    {"version" 1 "sequence" "0" "events" []})))
          rendered (handler (request "/api/render"
                                     (case-outcome/canonical-edn (document))))
          evaluated (handler (json-post-request "/api/eval"
                                                {"version" 1 "form" "(+ 1 2)"}))]
      (is (= 200 (:status rendered)))
      (is (= 200 (:status evaluated)))
      (is (= [[:case-outcome (document)]] @render-calls))
      (is (= ["(+ 1 2)"] @eval-calls)))))

(deftest replay-config-keys-are-an-all-or-nothing-pair
  (doseq [mutation [#(dissoc % :runtime-config)
                    #(dissoc % :allowed-scenarios)]]
    (let [data (try
                 (viewer/validate-config! (mutation (config)))
                 nil
                 (catch :default error (ex-data error)))]
      (is (= viewer/invalid-config (:type data)))
      (is (= :replay-config-must-be-a-pair (:reason data))))))

(deftest nonempty-run-presets-require-replay-config
  (let [data (try
               (viewer/validate-config!
                (assoc (eval-only-config) :run-presets [(run-preset)]))
               nil
               (catch :default error (ex-data error)))]
    (is (= viewer/invalid-config (:type data)))
    (is (= :run-presets-require-replay-config (:reason data))))
  (is (some? (viewer/validate-config! (eval-only-config))))
  (doseq [invalid [nil {} '() (with-meta [] {:invalid true})]]
    (let [data (try
                 (viewer/validate-config!
                  (assoc (eval-only-config) :run-presets invalid))
                 nil
                 (catch :default error (ex-data error)))]
      (is (= viewer/invalid-config (:type data)))
      (is (= :invalid-run-presets (:reason data))))))

(deftest eval-only-mode-fails-closed-on-replay-and-run-routes
  (let [render-calls (atom [])
        replay-calls (atom [])
        run-calls (atom 0)
        eval-calls (atom 0)
        body-read? (atom false)
        handler
        (viewer/make-handler
         (eval-only-config)
         (assoc (services render-calls replay-calls {:status :completed})
                :run-case (fn [_] (swap! run-calls inc))
                :evaluate-form! (fn [_] (swap! eval-calls inc))))
        run-request
        {:request-method :post
         :uri "/api/run"
         :headers {"content-type" "application/json"
                   "x-jolt-sim-capability" token}
         :body (recording-body body-read?)}
        replay-request
        (assoc (request "/api/replay"
                        (case-outcome/canonical-edn (document)))
               :body (recording-body body-read?))
        unauthorized-run
        (handler (assoc-in run-request
                           [:headers "x-jolt-sim-capability"] "wrong"))
        unauthorized-replay
        (handler (assoc-in replay-request
                           [:headers "x-jolt-sim-capability"] "wrong"))
        unauthorized-progress
        (handler (get-request "/api/replay-progress" "wrong"))
        unauthorized-presets
        (handler (get-request "/api/run-presets" "wrong"))
        run (handler run-request)
        replay (handler replay-request)
        progress (handler (get-request "/api/replay-progress"))
        presets (handler (get-request "/api/run-presets"))]
    (is (= 403 (:status unauthorized-run)))
    (is (= 403 (:status unauthorized-replay)))
    (is (= 403 (:status unauthorized-progress)))
    (is (= 403 (:status unauthorized-presets)))
    (is (= 404 (:status run)))
    (is (string/includes? (:body run) ":run-config-unavailable"))
    (is (= 404 (:status replay)))
    (is (string/includes? (:body replay) ":replay-unavailable"))
    (is (= 404 (:status progress)))
    (is (string/includes? (:body progress) ":replay-progress-unavailable"))
    (is (= 404 (:status presets)))
    (is (string/includes? (:body presets) ":run-presets-unavailable"))
    (is (false? @body-read?)
        "unavailable routes never read the request body")
    (is (= [] @render-calls))
    (is (= [] @replay-calls))
    (is (zero? @run-calls))
    (is (zero? @eval-calls))))

(defn- steppable-services
  "Trusted services over a real Session, recording every stepper call."
  [s step-calls]
  (assoc (services (atom []) (atom []) {:status :completed})
         :read-session-frame
         (fn [cursor]
           (viewer-session/read-frame s cursor))
         :step-session-frame!
         (fn [branch cursor]
           (swap! step-calls conj branch)
           (viewer-session/step-frame! s branch cursor))))

(deftest session-step-commits-once-and-identical-retry-is-stale
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        text (step-body "0" "0" "run" "2")
        committed (handler (step-request text))
        retry (handler (step-request text))]
    (is (= 200 (:status committed)))
    (is (= "application/edn; charset=utf-8"
           (get-in committed [:headers "Content-Type"])))
    (is (= {:version 1
            :status :committed
            :committed? true
            :ack {:branch {:revision 0 :action [:run 2]}
                  :revision 1}
            :frame-status :available}
           (edn/read-string (:body committed))))
    (is (= 409 (:status retry)))
    (is (= {:version 1
            :status :stale
            :committed? false
            :stale {:expected-revision 0
                    :actual-revision 1
                    :branch {:revision 0 :action [:run 2]}}
            :frame-status :available}
           (edn/read-string (:body retry))))
    (is (= [{:revision 0 :action [:run 2]}
            {:revision 0 :action [:run 2]}]
           @step-calls)
        "both requests reach stale validation at the trusted Session seam")
    (is (= 1 (:revision (session/snapshot s))))
    (is (= 2 (count (session/journal s)))
        "the identical request retried after commit is stale and never advances the revision again")))

(deftest session-step-json-acknowledges-the-exact-coordinate-and-stale-retry
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        request-json
        (fn []
          (assoc-in (step-request (step-body "0" "0" "run" "2"))
                    [:headers "accept"] "application/json"))
        committed (handler (request-json))
        stale (handler (request-json))
        committed-wire (json/read-str (:body committed))
        stale-wire (json/read-str (:body stale))]
    (is (= 200 (:status committed)))
    (is (= 409 (:status stale)))
    (is (= "application/json; charset=utf-8"
           (get-in committed [:headers "Content-Type"])))
    (is (= #{"version" "outcome" "committed" "revision" "kind"
             "value" "receiptEdn"}
           (set (keys committed-wire))))
    (is (= {"version" 1 "outcome" "committed" "committed" true
            "revision" "0" "kind" "run" "value" "2"}
           (dissoc committed-wire "receiptEdn")))
    (is (= {"version" 1 "outcome" "stale" "committed" false
            "revision" "0" "kind" "run" "value" "2"}
           (dissoc stale-wire "receiptEdn")))
    (is (= :committed
           (:status (edn/read-string (get committed-wire "receiptEdn")))))
    (is (= :stale
           (:status (edn/read-string (get stale-wire "receiptEdn")))))
    (is (= 1 (:revision (session/snapshot s))))))

(deftest session-step-json-distinguishes-safe-errors-from-ambiguous-server-errors
  (let [s (session/start (session-sim-config))
        handler (viewer/make-handler (config) (steppable-services s (atom [])))
        forbidden-request
        (assoc-in (step-request (step-body "0" "0" "run" "2") "wrong")
                  [:headers "accept"] "application/json")
        forbidden (handler forbidden-request)
        defective
        ((viewer/make-handler
          (config)
          (assoc (services (atom []) (atom []) {:status :completed})
                 :step-session-frame!
                 (fn [& _]
                   {:jolt.sim.session-view/type :wrong
                    :secret "must-not-cross"})))
         (assoc-in (step-request (step-body "0" "0" "run" "2"))
                   [:headers "accept"] "application/json"))]
    (is (= 403 (:status forbidden)))
    (is (= {"version" 1 "outcome" "error" "committed" false
            "error" "forbidden"}
           (json/read-str (:body forbidden))))
    (is (= 500 (:status defective)))
    (is (= "application/edn; charset=utf-8"
           (get-in defective [:headers "Content-Type"])))
    (is (string/includes? (:body defective) ":session-step-error"))
    (is (not (string/includes? (:body defective) "must-not-cross")))))

(deftest session-step-orders-authority-before-availability-gates-and-body
  (let [step-calls (atom [])
        read? (atom false)
        tracked-request
        (assoc (step-request (step-body "0" "0" "run" "2"))
               :body (recording-body read?))
        steppable (viewer/make-handler
                   (config)
                   (steppable-services (session/start (session-sim-config))
                                       step-calls))
        read-only (viewer/make-handler
                   (config)
                   (assoc (services (atom []) (atom []) {:status :completed})
                          :read-session-frame
                          (fn [_] (throw (ex-info "unused" {})))))
        forbidden (steppable (assoc-in tracked-request
                                       [:headers "x-jolt-sim-capability"]
                                       "wrong"))
        forbidden-read-only (read-only
                             (assoc-in tracked-request
                                       [:headers "x-jolt-sim-capability"]
                                       "wrong"))
        missing (read-only tracked-request)
        missing-wrong-media (read-only
                             (assoc-in tracked-request
                                       [:headers "content-type"]
                                       "text/plain"))
        wrong-media (steppable
                     (assoc-in tracked-request
                               [:headers "content-type"] "text/plain"))]
    (is (= 403 (:status forbidden)))
    (is (= 403 (:status forbidden-read-only))
        "authorization precedes service availability: no 404 oracle")
    (is (= 404 (:status missing)))
    (is (string/includes? (:body missing) ":session-step-unavailable"))
    (is (= 404 (:status missing-wrong-media))
        "service availability precedes the media-type check")
    (is (= 415 (:status wrong-media)))
    (is (string/includes? (:body wrong-media) ":expected-application-json"))
    (is (false? @read?))
    (is (= [] @step-calls)
        "rejected requests never invoke the trusted stepper")))

(deftest session-step-orders-instance-before-availability-media-body-and-service
  (let [text (step-body "0" "0" "run" "2")
        read? (atom false)
        step-calls (atom [])
        epoch-config (assoc (config) :session-instance-id session-instance-id)
        tracked (assoc (step-request text)
                       :body (recording-body read?))
        steppable
        (viewer/make-handler
         epoch-config
         (steppable-services (session/start (session-sim-config)) step-calls))
        read-only
        (viewer/make-handler
         epoch-config
         (assoc (services (atom []) (atom []) {:status :completed})
                :read-session-frame
                (fn [_] (throw (ex-info "unused" {})))))
        forbidden
        (steppable
         (-> tracked
             (assoc-in [:headers "x-jolt-sim-capability"] "wrong")
             (assoc-in [:headers "x-jolt-sim-session-instance"] "wrong")))
        missing (steppable tracked)
        stale (steppable
               (assoc-in tracked
                         [:headers "x-jolt-sim-session-instance"]
                         "ripple-session-instance-stale"))
        unavailable
        (read-only
         (assoc-in tracked
                   [:headers "x-jolt-sim-session-instance"]
                   session-instance-id))
        wrong-media
        (steppable
         (-> tracked
             (assoc-in [:headers "x-jolt-sim-session-instance"]
                       session-instance-id)
             (assoc-in [:headers "content-type"] "text/plain")))]
    (is (= 403 (:status forbidden))
        "authority precedes the epoch and reveals no match oracle")
    (is (nil? (get-in forbidden
                      [:headers "X-Jolt-Sim-Session-Instance"])))
    (doseq [response [missing stale]]
      (is (= 409 (:status response)))
      (is (string/includes? (:body response)
                            ":session-instance-mismatch"))
      (is (= session-instance-id
             (get-in response [:headers "X-Jolt-Sim-Session-Instance"]))))
    (is (= 404 (:status unavailable))
        "an exact epoch reaches service availability")
    (is (= 415 (:status wrong-media))
        "an exact epoch reaches media validation")
    (doseq [response [unavailable wrong-media]]
      (is (= session-instance-id
             (get-in response [:headers "X-Jolt-Sim-Session-Instance"]))))
    (is (false? @read?)
        "all epoch, availability, and media rejections precede body reads")
    (is (= [] @step-calls)
        "no rejected request invokes the trusted stepper")))

(deftest session-step-instance-is-a-coordinate-not-an-authority
  (let [text (step-body "0" "0" "run" "2")
        configured-session (session/start (session-sim-config))
        compatible-session (session/start (session-sim-config))
        configured-calls (atom [])
        compatible-calls (atom [])
        configured
        (viewer/make-handler
         (assoc (config) :session-instance-id session-instance-id)
         (steppable-services configured-session configured-calls))
        compatible
        (viewer/make-handler
         (config)
         (steppable-services compatible-session compatible-calls))
        exact (configured
               (step-request text token session-instance-id))
        omitted (compatible (step-request text))]
    (is (= 200 (:status exact)))
    (is (= session-instance-id
           (get-in exact [:headers "X-Jolt-Sim-Session-Instance"])))
    (is (= 200 (:status omitted))
        "omitting startup epoch configuration preserves the old protocol")
    (is (= [{:revision 0 :action [:run 2]}] @configured-calls))
    (is (= [{:revision 0 :action [:run 2]}] @compatible-calls))
    (is (= 1 (:revision (session/snapshot configured-session))))
    (is (= 1 (:revision (session/snapshot compatible-session))))))

(deftest session-step-rejects-malformed-noncanonical-and-wrong-type-bodies
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        before (session/snapshot s)
        cases [["{not json" :malformed-json]
               [(str (step-body "0" "0" "run" "2") " {\"extra\":true}")
                :malformed-json]
               [(str (step-body "0" "0" "run" "2") " trailing")
                :malformed-json]
               ["" :malformed-json]
               ["   " :malformed-json]
               ["[1,2,3]" :unexpected-keys]
               ["{\"version\":1,\"cursor\":\"0\"}" :unexpected-keys]
               [(str "{\"version\":1,\"cursor\":\"0\",\"surprise\":true,"
                     "\"branch\":{\"revision\":\"0\",\"kind\":\"run\","
                     "\"value\":\"2\"}}")
                :unexpected-keys]
               ["{\"version\":2,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"
                :unsupported-version]
               ["{\"version\":\"1\",\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"
                :unsupported-version]
               ["{\"version\":1.0,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"
                :unsupported-version]
               [(step-body "01" "0" "run" "2") :invalid-cursor]
               [(step-body "-1" "0" "run" "2") :invalid-cursor]
               [(step-body "+1" "0" "run" "2") :invalid-cursor]
               [(step-body "1 " "0" "run" "2") :invalid-cursor]
               [(step-body "99999999999999999999" "0" "run" "2")
                :decimal-out-of-range]
               [(step-body "9999999999999999999" "0" "run" "2")
                :decimal-out-of-range]
               ["{\"version\":1,\"cursor\":0,\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"
                :invalid-cursor]
               ["{\"version\":1,\"cursor\":\"0\",\"branch\":[\"run\",2]}"
                :invalid-branch]
               ["{\"version\":1,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\",\"surprise\":true}}"
                :invalid-branch]
               [(step-body "0" "01" "run" "2") :invalid-branch]
               [(step-body "0" "-1" "run" "2") :invalid-branch]
               [(step-body "0" "0" "RUN" "2") :unknown-kind]
               [(step-body "0" "0" "run-all" "2") :unknown-kind]
               [(step-body "0" "0" "run" "02") :invalid-value]
               [(step-body "0" "0" "run" "-2") :invalid-value]
               [(step-body "0" "0" "advance" "-0") :invalid-value]
               [(step-body "0" "0" "advance" "05") :invalid-value]
               [(step-body "0" "0" "advance" "-99999999999999999999")
                :decimal-out-of-range]
               ["{\"version\":1,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":2}}"
                :invalid-value]
               ["{\"version\":1,\"version\":1,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"value\":\"2\"}}"
                :duplicate-key]
               ["{\"version\":1,\"cursor\":\"0\",\"branch\":{\"revision\":\"0\",\"kind\":\"run\",\"kind\":\"run\",\"value\":\"2\"}}"
                :duplicate-key]
               [(step-body "9223372036854775808" "0" "run" "2")
                :decimal-out-of-range]
               [(step-body "0" "9223372036854775808" "run" "2")
                :decimal-out-of-range]
               [(step-body "0" "9223372036854775807" "run" "2")
                :decimal-out-of-range]
               [(step-body "0" "0" "run" "9223372036854775808")
                :decimal-out-of-range]
               [(step-body "0" "0" "advance" "9223372036854775808")
                :decimal-out-of-range]
               [(step-body "0" "0" "advance" "-9223372036854775809")
                :decimal-out-of-range]]]
    (doseq [[text reason] cases]
      (let [response (handler (step-request text))]
        (is (= 400 (:status response)) (pr-str text))
        (is (string/includes? (:body response) ":invalid-session-step")
            (pr-str text))
        (is (string/includes? (:body response) (str reason))
            (pr-str text))))
    (is (= [] @step-calls)
        "contract violations never invoke the trusted stepper")
    (is (= before (session/snapshot s)))
    (is (= 1 (count (session/journal s))))))

(deftest session-step-accepts-signed-advance-at-the-decimal-bound
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        ;; Long/MIN_VALUE is the 20-character signed bound. It parses, then
        ;; the kernel rejects it because only :run actions are enabled at
        ;; this machine state -- proving the bound did not reject early.
        response (handler
                  (step-request
                   (step-body "0" "0" "advance" "-9223372036854775808")))]
    (is (= 409 (:status response)))
    (is (string/includes? (:body response) ":session-step-rejected"))
    (is (= [{:revision 0 :action [:advance -9223372036854775808]}]
           @step-calls))
    (is (= 1 (count (session/journal s))))))

(deftest session-step-accepts-the-positive-payload-and-cursor-bound
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        maximum "9223372036854775807"]
    (doseq [text [(step-body "0" "0" "run" maximum)
                  (step-body "0" "0" "advance" maximum)]]
      (let [response (handler (step-request text))]
        (is (= 409 (:status response)))
        (is (string/includes? (:body response) ":session-step-rejected"))))
    (let [cursor (handler
                  (step-request (step-body maximum "0" "run" "2")))]
      (is (= 400 (:status cursor)))
      (is (string/includes? (:body cursor) ":invalid-session-cursor")))
    (is (= [{:revision 0 :action [:run 9223372036854775807]}
            {:revision 0 :action [:advance 9223372036854775807]}
            {:revision 0 :action [:run 2]}]
           @step-calls))
    (is (= 0 (:revision (session/snapshot s))))
    (is (= 1 (count (session/journal s))))))

(deftest session-step-rejects-oversized-bodies-before-stepping
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        oversized (apply str (repeat 4097 \space))
        declared (handler (step-request oversized))
        streamed (handler
                  {:request-method :post
                   :uri "/api/session-step"
                   :headers {"content-type" "application/json"
                             "x-jolt-sim-capability" token}
                   :body (body [(.getBytes (apply str (repeat 2048 \{))
                                           "UTF-8")
                                (.getBytes (apply str (repeat 2049 \}))
                                           "UTF-8")])})]
    (is (= 413 (:status declared)))
    (is (string/includes? (:body declared) ":request-too-large"))
    (is (= 413 (:status streamed)))
    (is (= [] @step-calls))
    (is (= 1 (count (session/journal s))))))

(deftest session-step-body-limit-is-independent-of-document-limit
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        text (step-body "0" "0" "run" "2")
        handler (viewer/make-handler
                 (assoc (config) :max-document-bytes 1)
                 (steppable-services s step-calls))
        response (handler (step-request text))]
    (is (> (alength (.getBytes ^String text "UTF-8")) 1))
    (is (= 200 (:status response)))
    (is (= [{:revision 0 :action [:run 2]}] @step-calls))
    (is (= 1 (:revision (session/snapshot s))))))

(deftest session-step-reports-stale-and-disabled-actions-without-mutation
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))]
    ;; A concurrent REPL step commits first, so the POSTed revision is stale.
    (session/step! s {:revision 0 :action [:run 2]})
    (let [stale (handler (step-request (step-body "1" "0" "run" "0")))]
      (is (= 409 (:status stale)))
      (is (= {:version 1
              :status :stale
              :committed? false
              :stale {:expected-revision 0
                      :actual-revision 1
                      :branch {:revision 0 :action [:run 0]}}
              :frame-status :available}
             (edn/read-string (:body stale))))
      (is (= 2 (count (session/journal s)))))
    ;; A well-formed revision-current action that is not enabled is rejected,
    ;; never applied: a non-runnable run target and an advance while only
    ;; runs are enabled.
    (doseq [text [(step-body "1" "1" "run" "1")
                  (step-body "1" "1" "advance" "6")]]
      (let [response (handler (step-request text))]
        (is (= 409 (:status response)) (pr-str text))
        (is (string/includes? (:body response) ":session-step-rejected"))
        (is (not (string/includes? (:body response) ":enabled"))
            "the machine's enabled set never crosses the wire")))
    (is (= 1 (:revision (session/snapshot s))))
    (is (= 2 (count (session/journal s))))))

(deftest session-step-reports-a-future-revision-as-stale
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        response (handler (step-request (step-body "0" "5" "run" "2")))]
    (is (= 409 (:status response)))
    (is (= {:version 1
            :status :stale
            :committed? false
            :stale {:expected-revision 5
                    :actual-revision 0
                    :branch {:revision 5 :action [:run 2]}}
            :frame-status :available}
           (edn/read-string (:body response))))
    (is (= [{:revision 5 :action [:run 2]}] @step-calls))
    (is (= 0 (:revision (session/snapshot s))))
    (is (= 1 (count (session/journal s))))))

(deftest session-step-commits-the-advertised-advance-branch
  (let [s (session/start (session-sim-config))
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))]
    (is (= 200 (:status (handler (step-request
                                  (step-body "0" "0" "run" "2"))))))
    (is (= 200 (:status (handler (step-request
                                  (step-body "0" "1" "run" "0"))))))
    (let [response (handler (step-request
                             (step-body "0" "2" "advance" "5")))]
      (is (= 200 (:status response)))
      (is (= {:version 1
              :status :committed
              :committed? true
              :ack {:branch {:revision 2 :action [:advance 5]}
                    :revision 3}
              :frame-status :available}
             (edn/read-string (:body response)))))
    (is (= [{:revision 0 :action [:run 2]}
            {:revision 1 :action [:run 0]}
            {:revision 2 :action [:advance 5]}]
           @step-calls))
    (is (= 3 (:revision (session/snapshot s))))
    (is (= 4 (count (session/journal s))))))

(deftest session-step-on-a-terminal-session-is-rejected-not-applied
  (let [s (session/start (session-sim-config))
        step-calls (atom [])]
    (run-to-terminal s)
    (let [handler (viewer/make-handler
                   (config) (steppable-services s step-calls))
          response (handler (step-request (step-body "0" "4" "run" "0")))]
      (is (= 409 (:status response)))
      (is (string/includes? (:body response) ":session-step-rejected"))
      (is (= 4 (:revision (session/snapshot s))))
      (is (= 5 (count (session/journal s)))))))

(deftest session-step-receipt-is-compact-and-secret-free
  (let [secret "DO-NOT-EMIT-STEP-SECRET"
        config-map (assoc (session-sim-config)
                          :world {:secret secret :seen []})
        s (session/start config-map)
        step-calls (atom [])
        handler (viewer/make-handler (config) (steppable-services s step-calls))
        committed (handler (step-request (step-body "0" "0" "run" "2")))
        stale (handler (step-request (step-body "0" "0" "run" "2")))]
    (is (= 200 (:status committed)))
    (is (= (trace/canonical-edn
            {:version 1
             :status :committed
             :committed? true
             :ack {:branch {:revision 0 :action [:run 2]}
                   :revision 1}
             :frame-status :available})
           (:body committed))
        "the receipt is exactly the compact closed projection")
    (doseq [forbidden [secret token ":projection" ":previews" ":journal"
                       ":events" ":world" ":branches" "demo.worker"
                       "demo.fast"]]
      (is (not (string/includes? (:body committed) forbidden))
          (str "committed receipt leaks " forbidden))
      (is (not (string/includes? (:body stale) forbidden))
          (str "stale receipt leaks " forbidden)))))

(deftest session-step-and-frame-share-one-admission-gate
  (let [entered (promise)
        release (promise)
        reads (atom 0)
        steps (atom 0)
        valid-frame (fn [cursor]
                      {:jolt.sim.session-view/type :frame
                       :revision 0 :status nil :projection {}
                       :branches [] :previews []
                       :journal {:cursor cursor :next-cursor cursor
                                 :count cursor :page-size 0
                                 :remaining? false :entries []}})
        committed-envelope {:jolt.sim.session-view/type :step-result
                            :status :committed
                            :committed? true
                            :ack {:branch {:revision 0 :action [:run 2]}
                                  :revision 1}
                            :frame {:jolt.sim.session-view/type :frame
                                    :revision 1}
                            :frame-error nil}
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :read-session-frame
                (fn [cursor]
                  (swap! reads inc)
                  (deliver entered true)
                  @release
                  (valid-frame cursor))
                :step-session-frame!
                (fn [_branch _cursor]
                  (swap! steps inc)
                  committed-envelope)))
        first-read (future (handler (get-request "/api/session-frame")))]
    (try
      (is (= true (deref entered 5000 ::timeout)))
      (let [read? (atom false)
            busy (handler
                  (assoc (step-request (step-body "0" "0" "run" "2"))
                         :body (recording-body read?)))
            unauthorized (handler
                          (step-request (step-body "0" "0" "run" "2")
                                        "wrong"))]
        (is (= 429 (:status busy)))
        (is (string/includes? (:body busy) ":session-step-busy"))
        (is (= 403 (:status unauthorized))
            "authorization is checked before the shared busy gate")
        (is (false? @read?)
            "the busy response precedes any streaming body read")
        (is (= 0 @steps))
        (is (= 1 @reads)))
      (finally
        (deliver release true)))
    (is (= 200 (:status (deref first-read 5000 {:status ::timeout}))))
    ;; The reverse direction: a step in flight blocks a frame read.
    (let [step-entered (promise)
          step-release (promise)
          gated (viewer/make-handler
                 (config)
                 (assoc (services (atom []) (atom []) {:status :completed})
                        :read-session-frame
                        (fn [cursor]
                          (swap! reads inc)
                          (valid-frame cursor))
                        :step-session-frame!
                        (fn [_branch _cursor]
                          (swap! steps inc)
                          (deliver step-entered true)
                          @step-release
                          committed-envelope)))
          first-step (future
                      (gated (step-request (step-body "0" "0" "run" "2"))))]
      (try
        (is (= true (deref step-entered 5000 ::timeout)))
        (let [busy (gated (get-request "/api/session-frame"))]
          (is (= 429 (:status busy)))
          (is (string/includes? (:body busy) ":session-frame-busy")))
        (finally
          (deliver step-release true)))
      (is (= 200 (:status (deref first-step 5000 {:status ::timeout}))))
      (is (= 200 (:status (gated (get-request "/api/session-frame")))))
      (is (= 200 (:status (gated (step-request (step-body "0" "0" "run" "2")))))
          "the shared gate is released after each command"))))

(deftest session-step-shares-the-document-body-consumer-gate
  (let [handler* (atom nil)
        busy-response* (atom nil)
        busy-body-read? (atom false)
        outcome {:status :completed :exit 0}
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :replay-document
                (fn [_ _]
                  ;; Re-enter with a step while the outer replay owns the
                  ;; body-consumer lease: the busy response must precede the
                  ;; streaming body, exactly like render/replay contention.
                  (reset!
                   busy-response*
                   (@handler*
                    {:request-method :post
                     :uri "/api/session-step"
                     :headers {"content-type" "application/json"
                               "x-jolt-sim-capability" token}
                     :body
                     (reify http-body/RequestBody
                       (body-recv [_]
                         (reset! busy-body-read? true)
                         (throw (ex-info "busy body was read" {})))
                       (body-bytes [_]
                         (reset! busy-body-read? true)
                         (throw (ex-info "busy body was read" {})))
                       (body-string [_ _]
                         (reset! busy-body-read? true)
                         (throw (ex-info "busy body was read" {}))))}))
                  outcome)
                :step-session-frame!
                (fn [_ _] (throw (ex-info "must not be invoked" {})))))]
    (reset! handler* handler)
    (let [response (handler
                    (request "/api/replay"
                             (case-outcome/canonical-edn (document))))]
      (is (= 200 (:status response)))
      (is (= 429 (:status @busy-response*)))
      (is (string/includes? (:body @busy-response*) ":viewer-busy"))
      (is (false? @busy-body-read?)))))

(deftest session-step-acknowledges-commit-when-the-frame-is-unavailable
  (let [handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :step-session-frame!
                (fn [_ _]
                  {:jolt.sim.session-view/type :step-result
                   :status :committed
                   :committed? true
                   :ack {:branch {:revision 0 :action [:run 2]}
                         :revision 1}
                   :frame nil
                   :frame-error
                   {:type :jolt.sim.session-view/coherence-failed
                    :phase :post-commit
                    :attempts 8
                    :max-attempts 8
                    :secret "must-not-cross"}})))
        response (handler (step-request (step-body "0" "0" "run" "2")))]
    (is (= 200 (:status response)))
    (is (= {:version 1
            :status :committed
            :committed? true
            :ack {:branch {:revision 0 :action [:run 2]}
                  :revision 1}
            :frame-status :unavailable
            :frame-error {:type :jolt.sim.session-view/coherence-failed
                          :phase :post-commit
                          :attempts 8
                          :max-attempts 8}}
           (edn/read-string (:body response)))
        "a committed command is acknowledged even when its frame is lost")
    (is (not (string/includes? (:body response) "must-not-cross")))))

(deftest session-step-reports-stale-when-the-refreshed-frame-is-unavailable
  (let [handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :step-session-frame!
                (fn [branch _]
                  {:jolt.sim.session-view/type :step-result
                   :status :stale
                   :committed? false
                   :ack nil
                   :stale {:type :jolt.sim.session/stale-branch
                           :expected-revision 0
                           :actual-revision 1
                           :branch branch
                           :secret "must-not-cross"}
                   :frame nil
                   :frame-error
                   {:type :jolt.sim.session-view/coherence-failed
                    :phase :stale-refresh
                    :attempts 8
                    :max-attempts 8
                    :secret "must-not-cross"}})))
        response (handler (step-request (step-body "0" "0" "run" "2")))]
    (is (= 409 (:status response)))
    (is (= {:version 1
            :status :stale
            :committed? false
            :stale {:expected-revision 0
                    :actual-revision 1
                    :branch {:revision 0 :action [:run 2]}}
            :frame-status :unavailable
            :frame-error {:type :jolt.sim.session-view/coherence-failed
                          :phase :stale-refresh
                          :attempts 8
                          :max-attempts 8}}
           (edn/read-string (:body response))))
    (is (not (string/includes? (:body response) "must-not-cross")))))

(deftest session-step-projects-service-extras-out-of-a-valid-receipt
  (let [secret "DO-NOT-EMIT-SERVICE-EXTRAS"
        handler
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :completed})
                :step-session-frame!
                (fn [_ _]
                  {:jolt.sim.session-view/type :step-result
                   :status :committed
                   :committed? true
                   :ack {:branch {:revision 0 :action [:run 2]
                                  :secret secret}
                         :revision 1
                         :secret secret}
                   :frame {:jolt.sim.session-view/type :frame
                           :world {:secret secret}}
                   :frame-error nil
                   :secret secret})))
        response (handler (step-request (step-body "0" "0" "run" "2")))]
    (is (= 200 (:status response)))
    (is (= {:version 1
            :status :committed
            :committed? true
            :ack {:branch {:revision 0 :action [:run 2]}
                  :revision 1}
            :frame-status :available}
           (edn/read-string (:body response))))
    (is (not (string/includes? (:body response) secret)))))

(deftest session-step-scrubs-unexpected-stepper-failures
  (doseq [stepper [(fn [_ _]
                     (throw (ex-info "stepper secret"
                                     {:secret "must-not-cross"})))
                   (fn [_ _] {:jolt.sim.session-view/type :step-result
                              :status :mystery})
                   (fn [_ _] {:jolt.sim.session-view/type :step-result
                              :status :committed :committed? true
                              :frame {:revision 1}})
                   (fn [_ _] {:jolt.sim.session-view/type :step-result
                              :status :committed :committed? true
                              :ack {:branch {:revision 0 :action [:run 2]}
                                    :revision 1}
                              :frame nil :frame-error nil})]]
    (let [handler
          (viewer/make-handler
           (config)
           (assoc (services (atom []) (atom []) {:status :completed})
                  :step-session-frame! stepper))
          response (handler (step-request (step-body "0" "0" "run" "2")))]
      (is (= 500 (:status response)))
      (is (string/includes? (:body response) ":session-step-error"))
      (is (not (string/includes? (:body response) "stepper secret")))
      (is (not (string/includes? (:body response) "must-not-cross"))))))

(deftest session-step-service-keys-are-closed-and-authoritative
  (let [unknown (try
                  (viewer/make-handler
                   (config)
                   (assoc (services (atom []) (atom []) {:status :completed})
                          :step-session-frame (fn [_ _] nil)))
                  nil
                  (catch :default error (ex-data error)))
        not-a-fn (try
                   (viewer/make-handler
                    (config)
                    (assoc (services (atom []) (atom []) {:status :completed})
                           :step-session-frame! :not-a-function))
                   nil
                   (catch :default error (ex-data error)))]
    (is (= viewer/invalid-config (:type unknown)))
    (is (= :unknown-service-keys (:reason unknown)))
    (is (= viewer/invalid-config (:type not-a-fn)))
    (is (= :invalid-services (:reason not-a-fn)))
    (is (fn? (viewer/make-handler
              (config)
              (assoc (services (atom []) (atom []) {:status :completed})
                     :step-session-frame! (fn [_ _] nil)))))
    ;; The command shape is fixed by the server: browser data can name only
    ;; the two closed action tags, never a function or capability.
    (let [handler (viewer/make-handler
                   (config)
                   (assoc (services (atom []) (atom []) {:status :completed})
                          :step-session-frame! (fn [_ _] nil)))
          response (handler (step-request (step-body "0" "0"
                                                     "step-session-frame!"
                                                     "2")))]
      (is (= 400 (:status response)))
      (is (string/includes? (:body response) ":unknown-kind")))))

(deftest start-steppable-session-installs-only-read-and-step-closures
  (let [s (session/start (session-sim-config))
        captured (atom nil)
        start-var (resolve 'jolt.sim.viewer/start!)]
    (with-redefs-fn
      {start-var
       (fn [supplied-config supplied-services]
         (reset! captured {:config supplied-config
                           :services supplied-services})
         :fake-server)}
      #(is (= :fake-server (viewer/start-steppable-session! (config) s))))
    (is (= (config) (:config @captured)))
    (is (= #{:render-trace :render-case-outcome :replay-document
             :read-session-frame :step-session-frame!}
           (set (keys (:services @captured)))))
    (is (= (viewer-session/read-frame s 0)
           ((get-in @captured [:services :read-session-frame]) 0)))
    (let [step! (get-in @captured [:services :step-session-frame!])
          committed (step! {:revision 0 :action [:run 2]} 0)
          retry (step! {:revision 0 :action [:run 2]} 0)]
      (is (= :committed (:status committed)))
      (is (true? (:committed? committed)))
      (is (= :stale (:status retry)))
      (is (false? (:committed? retry)))
      (is (= 1 (:revision (session/snapshot s))))
      (is (= 2 (count (session/journal s)))
          "the installed closure commits exactly once per exact branch"))))

;; --- Terminal replay activity page (opt-in worker lifecycle journal) ---
;;
;; The activity projection extends only the existing GET /api/replay-progress
;; route: a separate canonical cursor header, the trusted retained outcome via
;; process-explorer/read-activity-page, one closed JSON-safe page, and
;; secondary-only failure semantics. Journals are written through the real
;; jolt.sim.activity observer so recovery paging is exercised end to end.

(defn activity-config []
  (-> (config)
      (assoc-in [:runtime-config :activity-journal?] true)
      (assoc-in [:runtime-config :retain-completed-artifacts?] true)))

(defn- write-activity-journal! [run-dir events]
  (let [observer (activity/open-observer!
                  {:path (str (fs/path run-dir "activity.journal"))
                   :run-id (byte-array 16)})]
    (activity/call-with-observer
     observer
     (fn [] (doseq [event events] (activity/emit! event))))
    (activity/close-observer! observer)
    (is (= :healthy (:health (activity/observer-status observer))))
    (is (= (count events) (:accepted (activity/observer-status observer))))))

(defn- retained-outcome [run-dir]
  {:status :completed
   :exit 0
   :artifact-dir run-dir
   :activity {:observer-status nil}})

(defmacro with-retained-activity-dir
  "Runs body with a fresh activity artifact directory and deletes it only
  after normal completion with no new clojure.test failure/error. Unexpected
  exceptions and assertion failures retain the complete directory and print
  its path for restart-safe diagnosis."
  [[binding expression] & body]
  `(let [~binding ~expression
         failures-before# (+ (:fail @test/counters) (:error @test/counters))
         completed?# (volatile! false)]
     (try
       ~@body
       (vreset! completed?# true)
       (finally
         (if (and @completed?#
                  (= failures-before#
                     (+ (:fail @test/counters) (:error @test/counters))))
           (when (fs/exists? ~binding)
             (fs/delete-tree ~binding))
           (println "Retained unexpected Ripple activity artifacts at"
                    ~binding))))))

(deftest exceptional-run-retains-progress-diagnostics-and-journal-access
  (with-retained-activity-dir
    [run-dir (str (fs/create-temp-dir
                   {:prefix "jolt-sim-viewer-exceptional-run-"}))]
    (spit (str (fs/path run-dir "stderr.log")) "retained crash evidence")
    (write-activity-journal!
     run-dir
     [[:jolt.sim.explore/scenario-started nil nil {:scenario scenario}]])
    (let [handler
          (viewer/make-handler
           (-> (run-config)
               (assoc-in [:runtime-config :activity-journal?] true)
               (assoc-in [:runtime-config :retain-completed-artifacts?] true))
           {:render-trace identity
            :render-case-outcome identity
            :replay-document (fn [_ _] nil)
            :run-case
            (fn [runtime]
              ((:on-run-dir runtime) run-dir)
              (throw (ex-info "child death was not observed"
                              {:type :jolt.sim.process-explorer/worker-exit-unobserved
                               :artifact-dir run-dir})))})
          run-error
          (try
            (handler
             (json-post-request
              "/api/run"
              {"version" 2
               "presetId" "example.viewer/outbox-run"
               "regimeId" "example.viewer.regime/canonical"}))
            nil
            (catch :default error error))
          progress-response
          (handler
           (assoc-in (get-request "/api/replay-progress")
                     [:headers "x-jolt-sim-activity-cursor"] "0"))
          progress (json/read-str (:body progress-response))]
      (is (= :jolt.sim.process-explorer/worker-exit-unobserved
             (:type (ex-data run-error))))
      (is (= 200 (:status progress-response)))
      (is (= "failed" (get progress "status")))
      (is (= (run-selection) (get progress "selection")))
      (is (= "retained crash evidence" (get-in progress ["stderr" "text"])))
      (is (= "ok" (get-in progress ["activity" "status"])))
      (is (= ["jolt.sim.explore/scenario-started"]
             (mapv #(get % "tag") (get-in progress ["activity" "events"]))))
      (is (not (string/includes? (:body progress-response) run-dir))))))

(defn- replayed-activity-handler [config outcome]
  (let [handler
        (viewer/make-handler
         config
         {:render-trace (fn [_] "unused")
          :render-case-outcome (fn [_] "unused")
          :replay-document (fn [_ _] outcome)})
        replay-response
        (handler (request "/api/replay"
                          (case-outcome/canonical-edn (document))))]
    (is (= 200 (:status replay-response)))
    (is (not (string/includes? (:body replay-response) ":artifact-dir"))
        "the public replay response omits the private retention coordinate")
    (when-let [artifact-dir (:artifact-dir outcome)]
      (is (not (string/includes? (:body replay-response) artifact-dir))
          "the private artifact path never crosses the replay response"))
    handler))

(defn- activity-request
  ([cursor] (activity-request cursor token))
  ([cursor supplied-token]
   (cond-> (get-request "/api/replay-progress" supplied-token)
     (some? cursor)
     (assoc-in [:headers "x-jolt-sim-activity-cursor"] cursor))))

(deftest activity-journal-runtime-config-is-closed-and-fail-closed
  (is (some? (viewer/validate-config! (activity-config))))
  (doseq [[mutation reason]
          [[#(assoc-in % [:runtime-config :activity-journal?] :yes)
            :invalid-activity-journal]
           [#(assoc-in % [:runtime-config :retain-completed-artifacts?] false)
            :activity-journal-requires-retention]
           [#(update % :runtime-config dissoc :retain-completed-artifacts?)
            :activity-journal-requires-retention]]]
    (let [data (try
                 (viewer/validate-config! (mutation (activity-config)))
                 nil
                 (catch :default error (ex-data error)))]
      (is (= viewer/invalid-config (:type data)))
      (is (= reason (:reason data)))))
  (let [data
        (try
          (viewer/validate-config!
           (assoc (activity-config)
                  :activity-presentation-registry
                  {[:task/transition :op :sleep]
                   {:kind :acme.kind/invalid
                    :present (fn [_] {:summary "invalid" :fields []})}}))
          nil
          (catch :default error (ex-data error)))]
    (is (= viewer/invalid-config (:type data)))
    (is (= :invalid-activity-presentation-registry (:reason data)))))

(deftest replay-progress-activity-validates-auth-and-cursor-before-any-read
  (let [reader-calls (atom 0)
        read-page-var (resolve 'jolt.sim.process-explorer/read-activity-page)]
    (with-redefs-fn
      {read-page-var (fn [& _] (swap! reader-calls inc) nil)}
      #(let [handler
             (viewer/make-handler
              (activity-config)
              {:render-trace (fn [_] "unused")
               :render-case-outcome (fn [_] "unused")
               :replay-document (fn [_ _] {:status :completed :exit 0})})
             unauthorized (handler (activity-request nil "wrong"))
             malformed (handler (activity-request "-1"))
             leading-zero (handler (activity-request "00"))
             fractional (handler (activity-request "1.5"))
             overflow (handler (activity-request
                                "999999999999999999999999999999"))
             too-big (handler (activity-request "9999999999999999999"))
             idle (handler (activity-request "0"))]
         (is (= 403 (:status unauthorized)))
         (is (= 400 (:status malformed)))
         (is (= 400 (:status leading-zero)))
         (is (= 400 (:status fractional)))
         (is (= 400 (:status overflow)))
         (is (= 400 (:status too-big)))
         (is (string/includes? (:body malformed) ":invalid-activity-cursor"))
         (is (string/includes? (:body malformed) ":not-unsigned-decimal"))
         (is (string/includes? (:body leading-zero)
                               ":not-unsigned-decimal"))
         (is (string/includes? (:body overflow) ":out-of-range"))
         (is (string/includes? (:body too-big) ":out-of-range"))
         (is (= 200 (:status idle)))
         (is (nil? (get (json/read-str (:body idle)) "activity")))
         (is (= 0 @reader-calls)
             "auth and cursor validation never invoke the trusted page reader")))))

(deftest replay-progress-activity-stays-absent-while-idle-active-and-disabled
  (let [handler* (atom nil)
        mid-flight* (atom nil)
        handler
        (viewer/make-handler
         (activity-config)
         {:render-trace (fn [_] "unused")
          :render-case-outcome (fn [_] "unused")
          :replay-document
          (fn [_ _]
            (reset! mid-flight* (@handler* (activity-request "0")))
            {:status :completed :exit 0})})
        _ (reset! handler* handler)
        idle (handler (activity-request "0"))]
    (is (= 200 (:status idle)))
    (is (nil? (get (json/read-str (:body idle)) "activity")))
    (is (nil? (get-in idle [:headers "X-Jolt-Sim-Activity-Next-Cursor"])))
    (is (= 200 (:status (handler (request "/api/replay"
                                          (case-outcome/canonical-edn
                                           (document)))))))
    (is (= 200 (:status @mid-flight*)))
    (is (string/includes? (:body @mid-flight*) "\"status\":\"starting\""))
    (is (nil? (get (json/read-str (:body @mid-flight*)) "activity"))
        "activity stays absent while the replay is active")
    ;; Terminal with an outcome the run did not retain: the failure is a
    ;; closed secondary marker, never an HTTP error or a failed status.
    (let [terminal (handler (activity-request nil))
          wire (json/read-str (:body terminal))]
      (is (= 200 (:status terminal)))
      (is (= "completed" (get wire "status")))
      (is (= "unavailable" (get-in wire ["activity" "status"])))
      (is (= "not-retained" (get-in wire ["activity" "reason"])))
      (is (= 0 (get-in wire ["activity" "nextCursor"])))
      (is (= "0" (get-in terminal
                         [:headers "X-Jolt-Sim-Activity-Next-Cursor"]))))
    ;; With the trusted toggle disabled the route is byte-identical to the
    ;; pre-activity behavior even when the browser supplies a cursor.
    (let [disabled-handler
          (replayed-activity-handler
           (assoc-in (config) [:runtime-config :retain-completed-artifacts?]
                     true)
           {:status :completed :exit 0})
          terminal (disabled-handler (activity-request "0"))
          wire (json/read-str (:body terminal))]
      (is (= 200 (:status terminal)))
      (is (= "completed" (get wire "status")))
      (is (nil? (get wire "activity")))
      (is (nil? (get-in terminal
                        [:headers "X-Jolt-Sim-Activity-Next-Cursor"]))))))

(deftest replay-progress-activity-page-flows-from-the-retained-outcome
  (with-retained-activity-dir
    [run-dir (str (fs/create-temp-dir
                   {:prefix "jolt-sim-viewer-activity-page-"}))]
    (let [events [[:jolt.sim.explore/scenario-started nil nil
                   {:scenario scenario}]
                  [:jolt.sim.explore/scenario-completed nil nil
                   {:scenario scenario}]]
            _ (write-activity-journal! run-dir events)
            handler
            (replayed-activity-handler
             (assoc
              (activity-config)
              :activity-presentation-registry
              {:jolt.sim.explore/scenario-started
               {:kind :acme.kind/replay-started
                :present (fn [event]
                           {:summary "Application replay started"
                            :fields [{:label "Scenario"
                                      :value (get-in event [3 :scenario])}]})}})
             (retained-outcome run-dir))
            first-page (handler (activity-request nil))
            wire (json/read-str (:body first-page))
            page (get wire "activity")
            second-page (handler (activity-request "2"))
            second-wire (get (json/read-str (:body second-page)) "activity")
            beyond (handler (activity-request "3"))]
        (is (= 200 (:status first-page)))
        (is (= "completed" (get wire "status")))
        (is (= "ok" (get page "status")))
        (is (= 1 (get page "version")))
        (is (= 0 (get page "cursor")))
        (is (= 2 (get page "nextCursor")))
        (is (= 2 (get page "acceptedCount")))
        (is (false? (get page "remaining")))
        (is (= [{"sequence" 0
                 "tag" "jolt.sim.explore/scenario-started"
                 "kind" "acme.kind/replay-started"
                 "summary" "Application replay started"
                 "fields" [{"label" "Scenario"
                            "valueEdn" (trace/canonical-edn scenario)}]
                 "edn" (trace/canonical-edn (first events))}
                {"sequence" 1
                 "tag" "jolt.sim.explore/scenario-completed"
                 "kind" "jolt.sim.kind/scenario-completed"
                 "summary" "Scenario example.viewer/replay-case completed"
                 "fields" [{"label" "Scenario"
                            "valueEdn" (trace/canonical-edn scenario)}]
                 "edn" (trace/canonical-edn (second events))}]
               (get page "events")))
        (is (= "complete" (get-in page ["recovery" "status"])))
        (is (nil? (get-in page ["recovery" "reason"])))
        (is (false? (get-in page ["recovery" "imageTruncated"])))
        (is (nil? (get page "observer")))
        (is (= "2" (get-in first-page
                           [:headers "X-Jolt-Sim-Activity-Next-Cursor"])))
        (is (not (string/includes? (:body first-page) run-dir))
            "the trusted artifact path never crosses the wire")
        ;; The end cursor continues into an empty second page.
        (is (= 200 (:status second-page)))
        (is (= 2 (get second-wire "cursor")))
        (is (= 2 (get second-wire "nextCursor")))
        (is (= [] (get second-wire "events")))
        (is (false? (get second-wire "remaining")))
        (is (= "2" (get-in second-page
                           [:headers "X-Jolt-Sim-Activity-Next-Cursor"])))
        ;; A cursor past the recovered prefix is a typed 400, not a marker.
        (is (= 400 (:status beyond)))
        (is (string/includes? (:body beyond) ":invalid-activity-cursor"))
        (is (string/includes? (:body beyond) ":beyond-recovery"))
      (is (not (string/includes? (:body beyond) run-dir))))))

(deftest replay-progress-activity-second-page-continues-from-the-next-cursor
  (with-retained-activity-dir
    [run-dir (str (fs/create-temp-dir
                   {:prefix "jolt-sim-viewer-activity-cont-"}))]
    (let [events (mapv (fn [index]
                         [:acme.activity/tick nil nil {:index index}])
                       (range 40))
            _ (write-activity-journal! run-dir events)
            handler (replayed-activity-handler
                     (assoc (activity-config)
                            :max-document-bytes (* 1024 1024))
                     (retained-outcome run-dir))
            first-page (handler (activity-request nil))
            page0 (get (json/read-str (:body first-page)) "activity")
            second-page
            (handler (activity-request
                      (get-in first-page
                              [:headers "X-Jolt-Sim-Activity-Next-Cursor"])))
            page1 (get (json/read-str (:body second-page)) "activity")
            all-events (into (get page0 "events") (get page1 "events"))]
        (is (= 200 (:status first-page)))
        (is (= 32 (count (get page0 "events"))))
        (is (= 32 (get page0 "nextCursor")))
        (is (true? (get page0 "remaining")))
        (is (= "32" (get-in first-page
                            [:headers "X-Jolt-Sim-Activity-Next-Cursor"])))
        (is (= 200 (:status second-page)))
        (is (= 32 (get page1 "cursor")))
        (is (= 8 (count (get page1 "events"))))
        (is (= 40 (get page1 "nextCursor")))
        (is (false? (get page1 "remaining")))
        (is (= (range 40) (map #(get % "sequence") all-events))
            "pages concatenate into the complete accepted prefix")
        (is (= "jolt.sim.kind/raw-event"
               (get (first all-events) "kind"))
            "unknown activity tags keep the raw fallback through the server")
        (is (= "Raw event acme.activity/tick"
               (get (first all-events) "summary")))
      (is (not (string/includes? (:body first-page) run-dir)))
      (is (not (string/includes? (:body second-page) run-dir))))))

(deftest replay-progress-activity-failures-never-change-the-completed-status
  ;; Corrupt journal recovery: the page itself reports the bounded recovery
  ;; failure; the replay status and HTTP outcome are untouched.
  (with-retained-activity-dir
    [run-dir (str (fs/create-temp-dir
                   {:prefix "jolt-sim-viewer-activity-corrupt-"}))]
    (spit (str (fs/path run-dir "activity.journal"))
          "definitely not a journal image")
    (let [handler (replayed-activity-handler
                     (activity-config) (retained-outcome run-dir))
            response (handler (activity-request nil))
            wire (json/read-str (:body response))
            page (get wire "activity")]
        (is (= 200 (:status response)))
        (is (= "completed" (get wire "status")))
        (is (= "ok" (get page "status")))
        (is (= [] (get page "events")))
        (is (= "failed" (get-in page ["recovery" "status"])))
        (is (some? (get-in page ["recovery" "reason"])))
      (is (= "0" (get-in response
                         [:headers "X-Jolt-Sim-Activity-Next-Cursor"])))
      (is (not (string/includes? (:body response) run-dir)))))
  ;; A throwing trusted presenter: only the activity projection degrades.
  (with-retained-activity-dir
    [run-dir (str (fs/create-temp-dir
                   {:prefix "jolt-sim-viewer-activity-throw-"}))]
    (write-activity-journal!
     run-dir
     [[:jolt.sim.explore/scenario-started nil nil {:scenario scenario}]])
    (with-redefs
        [presentation/default-activity-registry
         {:jolt.sim.explore/scenario-started
          {:kind :acme.kind/boom
           :present (fn [_]
                      (throw (ex-info "secret-throwing-presenter" {})))}}]
        (let [handler (replayed-activity-handler
                       (activity-config) (retained-outcome run-dir))
              response (handler (activity-request nil))
              wire (json/read-str (:body response))
              page (get wire "activity")]
          (is (= 200 (:status response)))
          (is (= "completed" (get wire "status")))
          (is (= "unavailable" (get page "status")))
          (is (= "presentation-failed" (get page "reason")))
          (is (= 0 (get page "nextCursor")))
          (is (not (string/includes? (:body response)
                                     "secret-throwing-presenter")))
          (is (not (string/includes? (:body response) run-dir)))))))

(deftest replay-progress-activity-projection-alone-fails-when-oversized
  (with-retained-activity-dir
    [run-dir (str (fs/create-temp-dir
                   {:prefix "jolt-sim-viewer-activity-capped-"}))]
    (let [big (apply str (repeat 3000 \x))
            events (mapv (fn [index]
                           [:acme.activity/blob nil nil
                            {:payload big :index index}])
                         (range 4))
            _ (write-activity-journal! run-dir events)
            ;; The small default 4096-byte response cap cannot hold the page,
            ;; but easily holds the base progress body and the closed marker.
            handler (replayed-activity-handler
                     (activity-config) (retained-outcome run-dir))
            response (handler (activity-request nil))
            wire (json/read-str (:body response))
            page (get wire "activity")]
        (is (= 200 (:status response)))
        (is (= "completed" (get wire "status")))
        (is (= "too-large" (get page "status")))
        (is (= 4096 (get page "limit")))
        (is (< 4096 (get page "actual")))
        (is (= 0 (get page "cursor")))
        (is (= 4 (get page "nextCursor"))
            "the real page cursor still advances past the failed projection")
        (is (= "4" (get-in response
                           [:headers "X-Jolt-Sim-Activity-Next-Cursor"])))
      (is (not (string/includes? (:body response) run-dir)))
      (is (not (string/includes? (:body response) big))))))

(deftest retained-services-are-all-or-nothing
  (doseq [key [:read-retained-frame :command-retained!
               :reconcile-retained! :terminate-retained!]]
    (let [partial (assoc (services (atom []) (atom []) nil) key (fn [& _]))
          error (try
                  (viewer/make-handler (config) partial)
                  nil
                  (catch :default error error))]
      (is (= :retained-services-must-be-all-or-nothing
             (:reason (ex-data error))))))
  (is (fn? (viewer/make-handler (config) (retained-services (atom []))))))

(deftest retained-routes-check-authority-and-availability-before-body
  (let [reads (atom 0)
        counted-body
        (fn []
          (reify http-body/RequestBody
            (body-recv [_] (swap! reads inc) nil)
            (body-bytes [_] (byte-array 0))
            (body-string [_ _] "")))
        base {:request-method :post
              :uri "/api/retained-command"
              :headers {"content-type" "application/json"}
              :body (counted-body)}
        unavailable (viewer/make-handler
                     (config) (services (atom []) (atom []) nil))
        available (viewer/make-handler
                   (config) (retained-services (atom [])))]
    (is (= 403 (:status (available base))))
    (is (zero? @reads))
    (is (= 404 (:status
                (unavailable
                 (assoc-in base [:headers "x-jolt-sim-capability"] token)))))
    (is (zero? @reads))
    (is (= 415 (:status
                (available
                 (-> base
                     (assoc-in [:headers "x-jolt-sim-capability"] token)
                     (assoc-in [:headers "content-type"] "text/plain"))))))
    (is (zero? @reads))))

(deftest retained-frame-is-closed-redacted-and-uses-a-distinct-gate
  (let [nested (atom nil)
        handler-ref (atom nil)
        calls (atom [])
        svc (assoc (retained-services calls)
                   :read-retained-frame
                   (fn []
                     (swap! calls conj [:read])
                     (when-not @nested
                       (reset! nested
                               (@handler-ref
                                (get-request "/api/retained-frame"))))
                     retained-frame)
                   :render-case-outcome
                   (fn [_]
                     ;; The document gate is held while rendering, but retained
                     ;; inspection has a deliberately separate admission gate.
                     (let [response (@handler-ref
                                     (get-request "/api/retained-frame"))]
                       (is (= 200 (:status response))))
                     "<html>ok</html>"))
        handler (viewer/make-handler (config) svc)]
    (reset! handler-ref handler)
    (let [response (handler (get-request "/api/retained-frame"))
          wire (json/read-str (:body response))]
      (is (= 200 (:status response)))
      (is (= 429 (:status @nested)))
      (is (= "ready" (get-in wire ["coordinate" "status"])))
      (is (= "4" (get-in wire ["coordinate" "nextSequence"])))
      (is (string? (get wire "frameEdn")))
      (is (not (string/includes? (:body response) "private-retained-instance")))
      (is (not (string/includes? (:body response) "/tmp")))
      (is (not (string/includes? (:body response) "pid"))))
    (is (= 200
           (:status
            (handler
             (request "/api/render"
                      (case-outcome/canonical-edn (document)))))))))

(deftest retained-command-parses-one-canonical-edn-form-and-delegates-once
  (let [calls (atom [])
        handler (viewer/make-handler (config) (retained-services calls))
        response (handler
                  (json-post-request
                   "/api/retained-command"
                   {"version" 1
                    "commandEdn" "{:op :inspect :payload [0 255]}"}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= [[:command {:op :inspect :payload [0 255]}]] @calls))
    (is (= "completed" (get wire "outcome")))
    (is (true? (get wire "committed")))
    (is (= "4" (get wire "sequence")))
    (is (false? (get wire "truncated")))
    (is (map? (edn/read-string (get wire "receiptEdn"))))
    (let [bad (handler
               (json-post-request
                "/api/retained-command"
                {"version" 1 "commandEdn" ":one :two"}))]
      (is (= 400 (:status bad)))
      (is (= :invalid-retained-command
             (:error (edn/read-string (:body bad)))))
      (is (= 1 (count @calls))))))

(deftest retained-value-presentation-is-advisory-and-data-only
  (let [payload {:kind :example/network :hostile "<script>x</script>"}
        registry
        {:example/network
         {:kind :example.kind/topology
          :present
          (fn [value]
            {:summary "Example network"
             :fields [{:label "Hostile" :value (:hostile value)}]
             :graph
             {:directed? false
              :nodes [{:id "a" :label "A" :status :example.status/ready
                       :fields []}
                      {:id "b" :label "B" :status nil :fields []}]
              :edges [{:id "a--b" :from "a" :to "b" :label "link"
                       :status :example.status/connected :fields []}]}})}}
        svc (assoc (retained-services (atom []))
                   :command-retained! (fn [_]
                                        (retained-result :completed payload)))
        handler (viewer/make-handler
                 (assoc (config) :value-presentation-registry registry) svc)
        response (handler
                  (json-post-request "/api/retained-command"
                                     {"version" 1 "commandEdn" ":inspect"}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= "4" (get wire "sequence")))
    (is (= "Example network" (get-in wire ["presentation" "summary"])))
    (is (= "example/network"
           (get-in wire ["presentation" "sourceKind"])))
    (is (= "\"<script>x</script>\""
           (get-in wire ["presentation" "fields" 0 "valueEdn"])))
    (is (= ["a" "b"]
           (mapv #(get % "id")
                 (get-in wire ["presentation" "graph" "nodes"]))))
    (is (nil? (get wire "presentationError")))
    (is (= payload (:value (edn/read-string (get wire "receiptEdn")))))))

(deftest retained-presentation-failure-preserves-the-committed-receipt
  (let [payload {:kind :example/broken :value 42}
        registry
        {:example/broken
         {:kind :example.kind/broken
          :present (fn [_] (throw (ex-info "secret presenter failure" {})))}}
        svc (assoc (retained-services (atom []))
                   :command-retained! (fn [_]
                                        (retained-result :completed payload)))
        handler (viewer/make-handler
                 (assoc (config) :value-presentation-registry registry) svc)
        response (handler
                  (json-post-request "/api/retained-command"
                                     {"version" 1 "commandEdn" ":inspect"}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= "completed" (get wire "outcome")))
    (is (= "4" (get wire "sequence")))
    (is (= payload (:value (edn/read-string (get wire "receiptEdn")))))
    (is (nil? (get wire "presentation")))
    (is (= "presenter-threw" (get-in wire ["presentationError" "reason"])))
    (is (not (string/includes? (:body response) "secret presenter failure")))))

(deftest retained-unknown-value-kind-uses-the-generic-raw-view
  (let [payload {:kind :example/unknown :value 42}
        registry {:example/known
                  {:kind :example.kind/known
                   :present (fn [_] {:summary "known" :fields []})}}
        svc (assoc (retained-services (atom []))
                   :command-retained! (fn [_]
                                        (retained-result :completed payload)))
        response ((viewer/make-handler
                   (assoc (config) :value-presentation-registry registry) svc)
                  (json-post-request "/api/retained-command"
                                     {"version" 1 "commandEdn" ":inspect"}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= "jolt.sim.kind/raw-value"
           (get-in wire ["presentation" "kind"])))
    (is (= "example/unknown"
           (get-in wire ["presentation" "sourceKind"])))
    (is (= (trace/canonical-edn payload)
           (get-in wire ["presentation" "sourceEdn"])))
    (is (= payload (:value (edn/read-string (get wire "receiptEdn")))))))

(deftest oversized-presentation-is-dropped-before-definite-receipt-truncation
  (let [payload {:kind :example/large :value 42}
        registry
        {:example/large
         {:kind :example.kind/large
          :present (fn [_]
                     {:summary (apply str (repeat 400 \x)) :fields []})}}
        svc (assoc (retained-services (atom []))
                   :command-retained! (fn [_]
                                        (retained-result :completed payload)))
        cfg (assoc (config)
                   :max-document-bytes 1100
                   :value-presentation-registry registry)
        response ((viewer/make-handler cfg svc)
                  (json-post-request "/api/retained-command"
                                     {"version" 1 "commandEdn" ":inspect"}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= "completed" (get wire "outcome")))
    (is (true? (get wire "committed")))
    (is (= "4" (get wire "sequence")))
    (is (nil? (get wire "presentation")))
    (is (= "presentation-omitted-for-size"
           (get-in wire ["presentationError" "reason"])))
    (is (false? (get wire "truncated")))
    (is (string? (get wire "receiptEdn")))))

(deftest retained-application-failure-is-a-definite-http-200
  (let [calls (atom 0)
        presentation-calls (atom 0)
        registry
        {:application/rejected
         {:kind :application.kind/rejected
          :present (fn [_]
                     (swap! presentation-calls inc)
                     {:summary "rejected" :fields []})}}
        svc (assoc (retained-services (atom []))
                   :command-retained!
                   (fn [_]
                     (swap! calls inc)
                     (retained-result :failed
                                      {:type :application/rejected
                                       :reason :hostile-ack})))
        response ((viewer/make-handler
                   (assoc (config) :value-presentation-registry registry)
                   svc)
                  (json-post-request "/api/retained-command"
                                     {"version" 1
                                      "commandEdn" "{:op :deliver}"}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= 1 @calls))
    (is (zero? @presentation-calls))
    (is (= "failed" (get wire "outcome")))
    (is (true? (get wire "committed")))
    (is (nil? (get wire "presentation")))
    (is (nil? (get wire "presentationError")))
    (is (= :failed (:status (edn/read-string (get wire "receiptEdn")))))))

(deftest retained-large-post-commit-receipt-stays-definite
  (let [svc (assoc (retained-services (atom []))
                   :command-retained!
                   (fn [_]
                     (retained-result :completed
                                      {:payload (apply str (repeat 8000 \x))})))
        small-config (assoc (config) :max-document-bytes 512)
        response ((viewer/make-handler small-config svc)
                  (json-post-request "/api/retained-command"
                                     {"version" 1 "commandEdn" ":go"}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= "completed" (get wire "outcome")))
    (is (true? (get wire "committed")))
    (is (true? (get wire "truncated")))
    (is (nil? (get wire "receiptEdn")))
    (is (= "4" (get wire "sequence")))
    (is (not (string/includes? (:body response) (apply str (repeat 128 \x)))))))

(deftest retained-transport-uncertainty-is-bounded-and-never-retried
  (let [calls (atom 0)
        svc (assoc (retained-services (atom []))
                   :command-retained!
                   (fn [_]
                     (swap! calls inc)
                     (throw
                      (ex-info
                       "secret /tmp/private-retained"
                       {:type :jolt.sim.retained-process/transport-error
                        :reason :receipt-deadline
                        :status :uncertain
                        :sequence 4
                        :uncertain-sequence 4
                        :artifact-dir "/tmp/private-retained"}))))
        response ((viewer/make-handler (config) svc)
                  (json-post-request "/api/retained-command"
                                     {"version" 1 "commandEdn" ":go"}))
        wire (json/read-str (:body response))]
    (is (= 503 (:status response)))
    (is (= 1 @calls))
    (is (= "transport-error" (get wire "outcome")))
    (is (= "receipt-deadline" (get wire "reason")))
    (is (= "uncertain" (get wire "status")))
    (is (= "4" (get wire "sequence")))
    (is (= "4" (get wire "uncertainSequence")))
    (is (not (string/includes? (:body response) "private-retained")))))

(deftest retained-prepublication-failure-has-no-uncertain-coordinate
  (let [svc (assoc (retained-services (atom []))
                   :command-retained!
                   (fn [_]
                     (throw
                      (ex-info
                       "prepublication failure"
                       {:type :jolt.sim.retained-process/transport-error
                        :reason :publication-failed
                        :status :failed
                        :sequence 4
                        :uncertain-sequence nil}))))
        response ((viewer/make-handler (config) svc)
                  (json-post-request "/api/retained-command"
                                     {"version" 1 "commandEdn" ":go"}))
        wire (json/read-str (:body response))]
    (is (= 503 (:status response)))
    (is (= "4" (get wire "sequence")))
    (is (nil? (get wire "uncertainSequence")))))

(deftest retained-frame-transport-failure-is-also-a-bounded-503
  (let [calls (atom 0)
        svc (assoc (retained-services (atom []))
                   :read-retained-frame
                   (fn []
                     (swap! calls inc)
                     (throw
                      (ex-info
                       "secret frame transport"
                       {:type :jolt.sim.retained-process/transport-error
                        :reason :child-exited
                        :status :exited
                        :sequence 5
                        :uncertain-sequence 5
                        :artifact-dir "/tmp/private-frame"}))))
        response ((viewer/make-handler (config) svc)
                  (get-request "/api/retained-frame"))
        wire (json/read-str (:body response))]
    (is (= 503 (:status response)))
    (is (= 1 @calls))
    (is (= "transport-error" (get wire "outcome")))
    (is (= "child-exited" (get wire "reason")))
    (is (= "exited" (get wire "status")))
    (is (= "5" (get wire "sequence")))
    (is (= "5" (get wire "uncertainSequence")))
    (is (not (string/includes? (:body response) "private-frame")))))

(deftest retained-reconcile-and-terminate-use-closed-control-bodies
  (let [calls (atom [])
        handler (viewer/make-handler (config) (retained-services calls))
        reconciled (handler
                    (json-post-request "/api/retained-reconcile"
                                       {"version" 1}))
        terminated (handler
                    (json-post-request "/api/retained-terminate"
                                       {"version" 1}))]
    (is (= 200 (:status reconciled)))
    (is (= "completed"
           (get (json/read-str (:body reconciled)) "outcome")))
    (is (= 200 (:status terminated)))
    (let [wire (json/read-str (:body terminated))]
      (is (= "terminated" (get wire "outcome")))
      (is (= #{"version" "status" "outcome" "coordinate" "frameEdn"}
             (set (keys wire)))))
    (is (= [[:reconcile] [:terminate]] @calls))
    (let [bad (handler
               (json-post-request "/api/retained-reconcile"
                                  {"version" 1 "extra" true}))]
      (is (= 400 (:status bad)))
      (is (= 2 (count @calls))))))

(deftest retained-terminate-after-commit-has-a-closed-truncated-shape
  (let [calls (atom [])
        handler (viewer/make-handler
                 (assoc (config) :max-document-bytes 128)
                 (retained-services calls))
        response (handler
                  (json-post-request "/api/retained-terminate"
                                     {"version" 1}))
        wire (json/read-str (:body response))]
    (is (= 200 (:status response)))
    (is (= [[:terminate]] @calls))
    (is (= #{"version" "status" "outcome" "coordinate"
             "frameEdn" "truncated"}
           (set (keys wire))))
    (is (= "terminated" (get wire "outcome")))
    (is (true? (get wire "truncated")))
    (is (nil? (get wire "frameEdn")))))

(deftest retained-convenience-starts-capture-only-the-trusted-handle
  (let [captured (atom [])
        handle (Object.)
        eval (Object.)]
    (with-redefs [viewer/start!
                  (fn [_ services]
                    (swap! captured conj services)
                    :server)
                  retained-view/read-frame
                  (fn [actual]
                    (is (identical? handle actual))
                    retained-frame)
                  retained-view/command-frame!
                  (fn [actual command]
                    (is (identical? handle actual))
                    (retained-result :completed command))
                  retained-view/reconcile-frame!
                  (fn [actual]
                    (is (identical? handle actual))
                    (retained-result :reconcile :completed :ok retained-frame))
                  retained-view/terminate-frame!
                  (fn [actual]
                    (is (identical? handle actual))
                    (assoc retained-frame :status :terminated))
                  viewer-eval/service
                  (fn [actual]
                    (is (identical? eval actual))
                    :eval-service)]
      (is (= :server (viewer/start-retained-process! (config) handle)))
      (is (= :server
             (viewer/start-retained-eval-session! (config) handle eval)))
      (let [[plain combined] @captured]
        (let [expected #{:read-retained-frame :command-retained!
                         :reconcile-retained! :terminate-retained!}]
          (is (= expected (set (filter expected (keys plain))))))
        (is (= retained-frame ((:read-retained-frame plain))))
        (is (= :completed
               (:status ((:command-retained! plain) {:op :inspect}))))
        (is (= :completed (:status ((:reconcile-retained! plain)))))
        (is (= :terminated (:status ((:terminate-retained! plain)))))
        (is (= :eval-service (:evaluate-form! combined)))))))

;; --- Flow/effect Session attachment (wire v2) ---

(defn- flow-effect-state [status]
  (let [uncertain? (= :uncertain status)
        closed? (= :closed status)]
    {:status status
     :closed? closed?
     :ownership {:worker :borrowed}
     :effects {:seen-intents 1
               :records []
               :pending (when uncertain? {:id [:effect 4]})
               :remaining []}
     :worker {:status (if uncertain? :uncertain :ready)
              :next-sequence 4
              :uncertain-sequence (when uncertain? 4)}
     :commands {:step? (= :ready status)
                :reconcile-effect? uncertain?
                :reconcile-step? true
                :close? (not closed?)}}))

(defn- flow-effect-frame
  ([status] (flow-effect-frame status 0 0))
  ([status revision cursor]
   {:jolt.sim.session-view/type :frame
    :kind :jolt.sim.kind/flow-effect-session-frame
    :revision revision
    :status nil
    :projection {:world :fixture}
    :branches (if (= :ready status)
                [{:revision revision :action [:run 0]}]
                [])
    :previews []
    :journal {:cursor cursor :next-cursor cursor :count cursor
              :page-size 0 :remaining? false :entries []}
    :flow-effect (flow-effect-state status)}))

(defn- flow-effect-services [calls]
  (merge
   (services (atom []) (atom []) {:status :unused})
   {:read-session-frame
    (fn [cursor]
      (swap! calls conj [:read cursor])
      (flow-effect-frame :ready 0 cursor))
    :step-session-frame!
    (fn [branch cursor]
      (swap! calls conj [:step branch cursor])
      {:jolt.sim.session-view/type :step-result
       :kind :jolt.sim.kind/flow-effect-step-result
       :status :committed :committed? true
       :ack {:branch branch :revision (inc (:revision branch))}
       :flow-effect (flow-effect-state :uncertain)
       :frame (flow-effect-frame :uncertain (inc (:revision branch)) cursor)
       :frame-error nil})
    :reconcile-session-effect!
    (fn [cursor]
      (swap! calls conj [:effect-reconcile cursor])
      {:jolt.sim.flow-effect-view/type :effect-reconcile-result
       :kind :jolt.sim.kind/flow-effect-reconcile-result
       :operation :reconcile-effect :flow-committed? true
       :target {:intent-id [:intent 4] :sequence 4}
       :status :settled :effect {:state :settled}
       :frame (flow-effect-frame :ready 1 cursor)
       :frame-error nil})
    :reconcile-session-step
    (fn [branch cursor]
      (swap! calls conj [:step-reconcile branch cursor])
      {:jolt.sim.flow-effect-view/type :step-reconcile-result
       :kind :jolt.sim.kind/flow-effect-step-reconcile-result
       :operation :reconcile-step :submitted branch
       :status :committed :committed? true :revision 1
       :intent-ids [[:intent 4]]
       :frame (flow-effect-frame :ready 1 cursor)})
    :close-session!
    (fn [cursor]
      (swap! calls conj [:close cursor])
      {:jolt.sim.flow-effect-view/type :close-result
       :kind :jolt.sim.kind/flow-effect-close-result
       :operation :close :status :closed
       :worker {:ownership :borrowed :operation :none}
       :frame (flow-effect-frame :closed 1 cursor)})}))

(defn- flow-json-request [request]
  (assoc-in request [:headers "accept"] "application/json"))

(deftest flow-effect-frame-and-step-use-closed-v2-with-committed-uncertainty
  (let [calls (atom [])
        handler (viewer/make-handler
                 (assoc (config) :max-document-bytes (* 1024 1024))
                 (flow-effect-services calls))
        frame (handler (flow-json-request (get-request "/api/session-frame")))
        frame-wire (json/read-str (:body frame))
        step (handler
              (flow-json-request
               (step-request (step-body "0" "0" "run" "0"))))
        step-wire (json/read-str (:body step))]
    (is (= 200 (:status frame)))
    (is (= 2 (get frame-wire "version")))
    (is (= #{"version" "revision" "nextCursor" "stepEnabled"
             "frameEdn" "choices" "effect" "effectEdn"}
           (set (keys frame-wire))))
    (is (= {"status" "ready" "closed" false
            "workerOwnership" "borrowed" "stepEnabled" true
            "reconcileEnabled" false "closeEnabled" true
            "uncertainSequence" nil}
           (get frame-wire "effect")))
    (is (= 200 (:status step))
        "effect uncertainty does not erase the authoritative flow commit")
    (is (= #{"version" "outcome" "committed" "revision" "kind"
             "value" "receiptEdn" "effect" "effectEdn" "truncated"}
           (set (keys step-wire))))
    (is (= 2 (get step-wire "version")))
    (is (true? (get step-wire "committed")))
    (is (= "committed" (get step-wire "outcome")))
    (is (= "uncertain" (get-in step-wire ["effect" "status"])))
    (is (false? (get-in step-wire ["effect" "stepEnabled"])))
    (is (true? (get-in step-wire ["effect" "reconcileEnabled"])))
    (is (false? (get step-wire "truncated")))
    (is (= "4" (get-in step-wire ["effect" "uncertainSequence"])))
    (is (= [[:read 0]
            [:step {:revision 0 :action [:run 0]} 0]]
           @calls))))

(deftest flow-effect-controls-are-exact-and-preserve-borrowed-lifecycle
  (let [calls (atom [])
        handler (viewer/make-handler
                 (assoc (config) :max-document-bytes (* 1024 1024))
                 (flow-effect-services calls))
        effect-request
        (flow-json-request
         (json-post-request "/api/session-effect-reconcile" {"version" 1}))
        effect-response (handler effect-request)
        effect-wire (json/read-str (:body effect-response))
        reconcile-request
        (-> (step-request (step-body "0" "0" "run" "0"))
            (assoc :uri "/api/session-step-reconcile")
            flow-json-request)
        reconcile-response (handler reconcile-request)
        reconcile-wire (json/read-str (:body reconcile-response))
        close-response
        (handler
         (flow-json-request
          (json-post-request "/api/session-close" {"version" 1})))
        close-wire (json/read-str (:body close-response))]
    (is (= #{"version" "operation" "outcome" "flowCommitted"
             "effect" "effectEdn" "truncated"}
           (set (keys effect-wire))))
    (is (= "settled" (get effect-wire "outcome")))
    (is (true? (get effect-wire "flowCommitted")))
    (is (= #{"version" "operation" "outcome" "committed"
             "revision" "kind" "value" "effect" "effectEdn" "truncated"}
           (set (keys reconcile-wire))))
    (is (= "committed" (get reconcile-wire "outcome")))
    (is (= #{"version" "operation" "outcome" "closed"
             "effect" "effectEdn" "truncated"}
           (set (keys close-wire))))
    (is (= "closed" (get close-wire "outcome")))
    (is (= "borrowed" (get-in close-wire ["effect" "workerOwnership"])))
    (is (= [[:effect-reconcile 0]
            [:step-reconcile {:revision 0 :action [:run 0]} 0]
            [:close 0]]
           @calls)
        "the viewer exposes no raw retained command or termination call")))

(deftest flow-effect-definite-acknowledgments-survive-size-and-frame-failures
  (let [calls (atom [])
        tiny-handler
        (viewer/make-handler
         (assoc (config) :max-document-bytes 1)
         (flow-effect-services calls))
        step (tiny-handler
              (flow-json-request
               (step-request (step-body "0" "0" "run" "0"))))
        step-wire (json/read-str (:body step))
        control (tiny-handler
                 (flow-json-request
                  (json-post-request "/api/session-effect-reconcile"
                                     {"version" 1})))
        control-wire (json/read-str (:body control))
        frame-failure-services
        (assoc
         (flow-effect-services (atom []))
         :reconcile-session-effect!
         (fn [_]
           {:jolt.sim.flow-effect-view/type :effect-reconcile-result
            :kind :jolt.sim.kind/flow-effect-reconcile-result
            :operation :reconcile-effect
            :flow-committed? true
            :target {:intent-id [:intent 4] :sequence 4}
            :status :settled
            :effect {:state :settled}
            :flow-effect (flow-effect-state :ready)
            :frame nil
            :frame-error
            {:type :jolt.sim.flow-effect-view/coherence-failed
             :phase :post-effect-reconcile
             :attempts 8 :max-attempts 8}}))
        frame-failure-handler
        (viewer/make-handler
         (assoc (config) :max-document-bytes (* 1024 1024))
         frame-failure-services)
        frame-failure-response
        (frame-failure-handler
         (flow-json-request
          (json-post-request "/api/session-effect-reconcile" {"version" 1})))
        frame-failure-wire (json/read-str (:body frame-failure-response))]
    (is (= 200 (:status step)))
    (is (true? (get step-wire "committed")))
    (is (true? (get step-wire "truncated")))
    (is (nil? (get step-wire "receiptEdn")))
    (is (nil? (get step-wire "effectEdn")))
    (is (= "uncertain" (get-in step-wire ["effect" "status"])))
    (is (= 200 (:status control)))
    (is (true? (get control-wire "flowCommitted")))
    (is (true? (get control-wire "truncated")))
    (is (nil? (get control-wire "effectEdn")))
    (is (= 200 (:status frame-failure-response)))
    (is (= "settled" (get frame-failure-wire "outcome")))
    (is (= "ready" (get-in frame-failure-wire ["effect" "status"])))
    (is (false? (get frame-failure-wire "truncated")))))

(deftest flow-effect-control-orders-auth-availability-media-and-body
  (let [calls (atom [])
        read? (atom false)
        full (viewer/make-handler (config) (flow-effect-services calls))
        ordinary
        (viewer/make-handler
         (config)
         (assoc (services (atom []) (atom []) {:status :unused})
                :read-session-frame (fn [_] (flow-effect-frame :ready))))
        tracked
        {:request-method :post :uri "/api/session-close"
         :headers {"content-type" "application/json"
                   "x-jolt-sim-capability" token}
         :body (recording-body read?)}
        forbidden (full (assoc-in tracked
                                  [:headers "x-jolt-sim-capability"] "wrong"))
        unavailable (ordinary tracked)
        unavailable-wrong-media
        (ordinary (assoc-in tracked [:headers "content-type"] "text/plain"))
        wrong-media
        (full (assoc-in tracked [:headers "content-type"] "text/plain"))
        invalid-body
        (full (flow-json-request
               (json-post-request "/api/session-close"
                                  {"version" 1 "extra" true})))
        epoch-read? (atom false)
        epoch-handler
        (viewer/make-handler
         (assoc (config) :session-instance-id session-instance-id)
         (flow-effect-services (atom [])))
        missing-epoch
        (epoch-handler
         (assoc tracked :body (recording-body epoch-read?)))]
    (is (= 403 (:status forbidden)))
    (is (= 404 (:status unavailable)))
    (is (= 404 (:status unavailable-wrong-media)))
    (is (= 415 (:status wrong-media)))
    (is (= 400 (:status invalid-body)))
    (is (= 409 (:status missing-epoch)))
    (is (false? @read?))
    (is (false? @epoch-read?)
        "producer epoch rejection precedes body consumption")
    (is (= [] @calls))))

(deftest flow-effect-control-shares-the-session-single-flight-gate
  (let [entered (promise)
        release (promise)
        calls (atom [])
        services-map
        (assoc (flow-effect-services calls)
               :reconcile-session-effect!
               (fn [cursor]
                 (deliver entered true)
                 @release
                 {:jolt.sim.flow-effect-view/type :effect-reconcile-result
                  :operation :reconcile-effect :flow-committed? true
                  :status :settled :effect {:state :settled}
                  :frame (flow-effect-frame :ready 1 cursor)
                  :frame-error nil}))
        handler (viewer/make-handler (config) services-map)
        first-response
        (future
          (handler
           (flow-json-request
            (json-post-request "/api/session-effect-reconcile"
                               {"version" 1}))))]
    (try
      (is (= true (deref entered 5000 ::timeout)))
      (is (= 429 (:status
                  (handler (get-request "/api/session-frame")))))
      (finally
        (deliver release true)))
    (is (= 200 (:status (deref first-response 5000 {:status ::timeout}))))))

(deftest start-flow-effect-session-installs-five-opaque-closures-only
  (let [bridge (Object.)
        captured (atom nil)
        calls (atom [])]
    (with-redefs [viewer/start!
                  (fn [supplied-config supplied-services]
                    (reset! captured {:config supplied-config
                                      :services supplied-services})
                    :server)
                  flow-effect-view/read-frame
                  (fn [actual cursor]
                    (is (identical? bridge actual))
                    (swap! calls conj [:read cursor])
                    :frame)
                  flow-effect-view/step-frame!
                  (fn [actual branch cursor]
                    (is (identical? bridge actual))
                    (swap! calls conj [:step branch cursor])
                    :step)
                  flow-effect-view/reconcile-effect-frame!
                  (fn [actual cursor]
                    (is (identical? bridge actual))
                    (swap! calls conj [:effect-reconcile cursor])
                    :effect)
                  flow-effect-view/reconcile-step-frame
                  (fn [actual branch cursor]
                    (is (identical? bridge actual))
                    (swap! calls conj [:step-reconcile branch cursor])
                    :reconciled)
                  flow-effect-view/close-frame!
                  (fn [actual cursor]
                    (is (identical? bridge actual))
                    (swap! calls conj [:close cursor])
                    :closed)]
      (is (= :server
             (viewer/start-flow-effect-session! (config) bridge)))
      (let [service-map (:services @captured)
            installed (select-keys service-map
                                   [:read-session-frame
                                    :step-session-frame!
                                    :reconcile-session-effect!
                                    :reconcile-session-step
                                    :close-session!])
            branch {:revision 0 :action [:run 0]}]
        (is (= 5 (count installed)))
        (is (not-any? #(contains? service-map %)
                      [:read-retained-frame :command-retained!
                       :reconcile-retained! :terminate-retained!]))
        (is (= :frame ((:read-session-frame installed) 0)))
        (is (= :step ((:step-session-frame! installed) branch 0)))
        (is (= :effect ((:reconcile-session-effect! installed) 0)))
        (is (= :reconciled
               ((:reconcile-session-step installed) branch 0)))
        (is (= :closed ((:close-session! installed) 0)))
        (is (= [[:read 0] [:step branch 0] [:effect-reconcile 0]
                [:step-reconcile branch 0] [:close 0]]
               @calls))))))

(deftest stopping-viewer-does-not-close-a-flow-effect-bridge
  (let [stops (atom [])
        bridge-calls (atom 0)]
    (with-redefs [jolt.http.server/stop-server
                  (fn [server] (swap! stops conj server) :stopped)
                  flow-effect-view/close-frame!
                  (fn [& _] (swap! bridge-calls inc))]
      (is (= :stopped (viewer/stop! :server)))
      (is (= [:server] @stops))
      (is (zero? @bridge-calls)))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.viewer-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    (when (pos? failures)
      (System/exit 1))))
