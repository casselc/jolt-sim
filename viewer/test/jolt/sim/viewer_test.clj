(ns jolt.sim.viewer-test
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.http.body :as http-body]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]
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
    (is (string/includes? (:body script)
                          "const replayRequest = request(\"/api/replay\")"))
    (is (= "no-store" (get-in shell [:headers "Cache-Control"])))
    (is (string/includes?
         (get-in shell [:headers "Content-Security-Policy"])
         "default-src 'none'"))))

(deftest ephemeral-loopback-port-is-valid
  (is (= 0 (:port (viewer/validate-config! (assoc (config) :port 0))))))

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

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.viewer-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    (when (pos? failures)
      (System/exit 1))))
