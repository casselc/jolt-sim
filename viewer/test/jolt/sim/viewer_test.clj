(ns jolt.sim.viewer-test
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.http.body :as http-body]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.viewer.experiment :as viewer-experiment]
            [jolt.sim.viewer.session :as viewer-session]
            [teensyp.client :as client]))

(def token "0123456789abcdef0123456789abcdef")
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
    (is (string/includes? (:body script) "file.disabled = busy"))
    (is (string/includes? (:body script) "kind.disabled = busy"))
    (is (string/includes? (:body script)
                          "X-Jolt-Sim-Document-Kind"))
    (is (string/includes? (:body shell)
                          "value=\"experiment-plan\""))
    (is (string/includes? (:body script)
                          "const replayRequest = request(\"/api/replay\")"))
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
     :checks [{:fields {:monitor-specs [{:id :example.check/one :check hidden}]
                       :presentation-registry
                       {:check/event {:kind :example.kind/check :present hidden}}}}]
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

;; --- Viewer-side session adapter (jolt.sim.viewer.session) ---
;;
;; UI-neutral read/step slice over one cooperative Session. The core logic is
;; exercised through the public API with a real Session, and through the
;; private ops seam (resolved below) where a concurrent step must be scripted
;; deterministically: bounded coherence and post-commit frame failure.

(def ^:private read-frame-ops-var
  (resolve 'jolt.sim.viewer.session/read-frame*))

(def ^:private step-frame-ops-var
  (resolve 'jolt.sim.viewer.session/step-frame*))

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

(deftest session-frame-initial-read-is-coherent-and-closed
  (let [s (session/start (session-sim-config))
        frame (viewer-session/read-frame s 0)]
    (is (= #{:jolt.sim.viewer.session/type :revision :status :projection
             :branches :previews :journal}
           (set (keys frame))))
    (is (= :frame (get frame :jolt.sim.viewer.session/type)))
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
        (is (= :jolt.sim.viewer.session/invalid-cursor (:type data)))
        (is (= :not-a-non-negative-integer (:reason data)))))
    (let [data (caught-data #(viewer-session/read-frame s 5))]
      (is (= :jolt.sim.viewer.session/invalid-cursor (:type data)))
      (is (= :ahead-of-journal (:reason data)))
      (is (= 5 (:cursor data)))
      (is (= 1 (:journal-count data))))
    (let [before (session/snapshot s)
          data (caught-data
                #(viewer-session/step-frame! s {:revision 0 :action [:run 2]} -1))]
      (is (= :jolt.sim.viewer.session/invalid-cursor (:type data)))
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
    (is (= :jolt.sim.viewer.session/coherence-failed (:type data)))
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
    (is (= {:type :jolt.sim.viewer.session/coherence-failed
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

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.viewer-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    (when (pos? failures)
      (System/exit 1))))
