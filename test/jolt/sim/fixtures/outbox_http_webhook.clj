(ns jolt.sim.fixtures.outbox-http-webhook
  "Ordinary whole-application fixture for JSON command intake followed by
  JSON HTTP webhook delivery.

  The fixture has no simulator dependency. It composes the existing routed
  JSON command seam and SQLite outbox adapter with jolt-http's public server
  and jolt.example.outbox.http-webhook's public jolt.http-client sender. The
  exact same function therefore runs against host sockets/SQLite or beneath
  the existing POSIX/SQLite controller worlds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [jolt.example.outbox.http-webhook :as webhook]
            [jolt.example.outbox.sqlite :as store]
            [jolt.http.body :as http-body]
            [jolt.http.server :as http]
            [jolt.sim.fixtures.http-sqlite :as http-fixture]
            [jolt.sim.fixtures.outbox-json-delivery :as json-command]))

(def default-command json-command/default-command)
(def default-attempt-id "delivery-1-attempt-1")

(def accepted-scenarios [:accepted :accepted-json-whitespace])

(def hostile-scenarios
  [:non-2xx :malformed-json :trailing-json
   :mismatched-attempt :mismatched-durable])

(defn- fail! [reason detail]
  (throw
   (ex-info
    (str "outbox HTTP webhook fixture: " (name reason))
    {:type :jolt.sim.fixtures.outbox-http-webhook/invalid-flow
     :reason reason
     :detail detail})))

(defn- stable-error-summary [error]
  {:class (str (class error))
   :message (or (ex-message error)
                (try (jolt.host/condition-message error)
                     (catch :default _ nil)))
   :data (when-let [data (ex-data error)]
           (into {}
                 (map (fn [[key value]] [(str key) (pr-str value)]))
                 data))})

(defn- cleanup-attempt [operation thunk]
  (try
    (thunk)
    nil
    (catch :default error
      {:operation operation :error error})))

(defn- throw-with-cleanup! [primary cleanup-errors]
  (let [summaries
        (mapv (fn [{:keys [operation error]}]
                (assoc (stable-error-summary error) :operation operation))
              cleanup-errors)]
    (cond
      (and primary (seq cleanup-errors))
      (throw
       (ex-info
        (or (ex-message primary) (str primary))
        (assoc (or (ex-data primary) {})
               :outbox-http-webhook/primary-error
               (stable-error-summary primary)
               :outbox-http-webhook/cleanup-errors summaries)
        primary))

      primary
      (throw primary)

      (seq cleanup-errors)
      (throw
       (ex-info
        "outbox HTTP webhook fixture cleanup failed"
        {:type :jolt.sim.fixtures.outbox-http-webhook/cleanup-failure
         :outbox-http-webhook/cleanup-errors summaries}
        (:error (first cleanup-errors)))))))

(defn- json-content-type? [value]
  (and (string? value)
       (= "application/json"
          (-> value (str/split #";" 2) first str/trim str/lower-case))))

(defn- decode-json-exact [context text]
  (try
    (json/read-str text :extra-data-fn json/on-extra-throw)
    (catch :default error
      (fail! :invalid-json
             {:context context :message (ex-message error)}))))

(defn- command-phase! [conn command]
  (let [seam json-command/json-http-seam
        evidence* (atom nil)
        cycle
        (http-fixture/run-request-cycle
         ((:handler-for seam) conn command evidence* nil)
         (fn [host port]
           ((:request-bytes-for seam) command host port)))
        parsed (:parsed cycle)]
    (when-not (= (:expected-status seam) (:status parsed))
      (fail! :command-status
             {:expected (:expected-status seam) :actual (:status parsed)}))
    (when-not @evidence*
      (fail! :missing-command-evidence {}))
    {:evidence @evidence*
     :http {:status (:status parsed)
            :content-type (get (:headers parsed) "content-type")
            :response ((:decode-response seam) (:body parsed))
            :server-errors (:server-errors cycle)
            :close-results (:close-results cycle)}}))

(defn- receiver-response [scenario expected-ack]
  (case scenario
    :accepted
    {:status 200 :body (json/write-str expected-ack)}

    :accepted-json-whitespace
    {:status 200 :body (str (json/write-str expected-ack) "\r\n\t ")}

    :non-2xx
    {:status 503 :body (json/write-str expected-ack)}

    :malformed-json
    {:status 200 :body "{\"type\":\"outbox_delivery_ok\""}

    :trailing-json
    {:status 200 :body (str (json/write-str expected-ack) " trailing")}

    :mismatched-attempt
    {:status 200
     :body (json/write-str (assoc expected-ack
                                  "attempt-id" "wrong-attempt"))}

    :mismatched-durable
    {:status 200
     :body (json/write-str (assoc-in expected-ack
                                     ["durable" "outbox-id"] 999))}

    (fail! :unknown-scenario {:scenario scenario})))

(defn- receiver-handler [scenario received]
  (fn [request]
    (when-not (= [:post "/v1/outbox-deliveries"]
                 [(:request-method request) (:uri request)])
      (fail! :unexpected-receiver-route
             {:method (:request-method request) :uri (:uri request)}))
    (when-not (json-content-type?
               (get (:headers request) "content-type"))
      (fail! :unexpected-receiver-content-type
             {:headers (:headers request)}))
    (let [wire (decode-json-exact
                :delivery-request
                (http-body/body-string (:body request) "UTF-8"))
          _ (swap! received conj wire)
          durable (get wire "durable")
          expected-ack
          {"type" "outbox_delivery_ok"
           "durable" durable
           "attempt-id" (get wire "attempt-id")}
          response (receiver-response scenario expected-ack)]
      {:status (:status response)
       :headers {"Content-Type" "application/json"}
       :body (:body response)})))

(defn- one-pending-row! [state]
  (let [rows (filterv #(= :pending (:status %)) (:outbox state))]
    (when-not (= 1 (count rows))
      (fail! :unexpected-pending-count {:count (count rows)}))
    (first rows)))

(defn exercise
  "Runs one fixed command and one webhook scenario.

  `scenario` is one of accepted-scenarios or hostile-scenarios. Hostile
  webhook refusals are captured as bounded evidence only when they carry the
  production sender's typed refusal; every unrelated failure propagates. The
  connection remains open through command COMMIT, pending reload, webhook
  exchange, optional mark, and final reload."
  ([] (exercise :accepted))
  ([scenario]
   (when-not (contains? (set (concat accepted-scenarios hostile-scenarios))
                        scenario)
     (fail! :unknown-scenario {:scenario scenario}))
   (let [received (atom [])
         receiver-errors (atom [])
         server
         (http/run-server
          (receiver-handler scenario received)
          :port 0
          :reuse-address? true
          :error-logger #(swap! receiver-errors conj (stable-error-summary %)))
         connection* (atom nil)
         body
         (try
           {:value
            (let [conn (jdbc/connection "sqlite::memory:")
                  _ (reset! connection* conn)
                  _ (store/init-schema! conn)
                  command (command-phase! conn default-command)
                  pending-state (store/load-state conn)
                  row (one-pending-row! pending-state)
                  url (str "http://127.0.0.1:" (:port server)
                           "/v1/outbox-deliveries")
                  delivery-outcome
                  (try
                    {:value (webhook/deliver-pending!
                             conn url row default-attempt-id)}
                    (catch :default error
                      (if (= :jolt.example.outbox.http-webhook/delivery-refused
                             (:type (ex-data error)))
                        {:error error}
                        (throw error))))
                  final-state (store/load-state conn)
                  accepted? (contains? (set accepted-scenarios) scenario)]
              (when (and accepted? (:error delivery-outcome))
                (throw (:error delivery-outcome)))
              (when (and (not accepted?) (:value delivery-outcome))
                (fail! :hostile-ack-authorized-marking
                       {:scenario scenario
                        :delivery (:value delivery-outcome)}))
              {:scenario scenario
               :command command
               :pending-state pending-state
               :delivery (if-let [value (:value delivery-outcome)]
                           value
                           {:refused
                            {:reason (:reason (ex-data (:error delivery-outcome)))
                             :detail (:detail (ex-data (:error delivery-outcome)))}})
               :store-state final-state})}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep identity
                [(when-let [conn @connection*]
                   (cleanup-attempt :sqlite-connection-close #((:close conn))))
                 (cleanup-attempt :receiver-stop #(http/stop-server server))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     (assoc (:value body)
            :receiver {:requests @received
                       :server-errors @receiver-errors}))))
