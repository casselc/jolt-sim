(ns jolt.sim.viewer-test
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.http.body :as http-body]
            [jolt.sim.case-outcome :as case-outcome]
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
  ([uri text] (request uri text token))
  ([uri text supplied-token]
   {:request-method :post
    :uri uri
    :headers {"content-type" "application/edn"
              "content-length" (str (count (.getBytes ^String text "UTF-8")))
              "x-jolt-sim-capability" supplied-token}
    :body (body [(.getBytes ^String text "UTF-8")])}))

(defn services [render-calls replay-calls replay-outcome]
  {:render-document
   (fn [doc]
     (swap! render-calls conj doc)
     "<html>validated</html>")
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

(defn- read-response! [connection]
  (let [scratch (byte-array 4096)]
    (loop [chunks []]
      (if-let [length (client/receive-into!
                       connection scratch 0 (alength scratch)
                       {:timeout-ms 5000})]
        (recur (conj chunks (copy-of-length scratch length)))
        (String. (concat-byte-arrays chunks) "UTF-8")))))

(defn- request-over-loopback! [port method uri headers body-text]
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
                                   {:connect-timeout-ms 5000})]
    (try
      (client/send-all! connection (.getBytes request-text "UTF-8")
                        {:timeout-ms 5000})
      (read-response! connection)
      (finally
        (client/close! connection)))))

(deftest startup-config-is-closed-and-fail-closed
  (is (= 8788 (:port (viewer/validate-config! (config)))))
  (doseq [[mutation reason]
          [[#(assoc % :surprise true) :unknown-keys]
           [#(assoc % :capability-token "short") :weak-capability-token]
           [#(assoc % :port 0) :invalid-port]
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
                 {:render-document identity
                  :replay-document (fn [_ _] nil)})
        shell (handler {:request-method :get :uri "/"})
        script (handler {:request-method :get :uri "/viewer.js"})]
    (is (= 200 (:status shell)))
    (is (string/includes? (:body shell) "retained-case viewer"))
    (is (not (string/includes? (:body shell) token)))
    (is (= 200 (:status script)))
    (is (string/includes? (:body script) "textContent"))
    (is (not (string/includes? (:body script) "innerHTML")))
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
    (is (= "<html>validated</html>" (:body response)))
    (is (= [doc] @render-calls))
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
                             "x-jolt-sim-capability" token}
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
    (is (= [[doc (:runtime-config config)]] @replay-calls))
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
                   {:render-document (fn [_] "unused")
                    :replay-document (fn [_ _] outcome)})
          response (handler
                    (request "/api/replay"
                             (case-outcome/canonical-edn (document))))]
      (is (= 200 (:status response)))
      (is (= outcome (edn/read-string (:body response)))))))

(deftest unexpected-service-errors-propagate-to-the-http-error-boundary
  (let [error (ex-info "renderer defect" {:type ::renderer-defect})
        handler (viewer/make-handler
                 (config)
                 {:render-document (fn [_] (throw error))
                  :replay-document (fn [_ _] nil)})]
    (is (identical?
         error
         (try
           (handler (request "/api/render"
                             (case-outcome/canonical-edn (document))))
           nil
           (catch :default caught caught))))))

(deftest unknown-routes-do-not-read-or-run-a-document
  (let [handler (viewer/make-handler
                 (config)
                 {:render-document (fn [_] (throw (ex-info "called" {})))
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
              "X-Jolt-Sim-Capability" token}
             encoded)]
        (is (pos? port))
        (is (string/starts-with? shell "HTTP/1.1 200"))
        (is (string/includes? shell "jolt-sim retained-case viewer"))
        (is (string/starts-with? rendered "HTTP/1.1 200"))
        (is (string/includes? rendered "example.viewer/replay-case")))
      (finally
        (viewer/stop! server)))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.viewer-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    (when (pos? failures)
      (System/exit 1))))
