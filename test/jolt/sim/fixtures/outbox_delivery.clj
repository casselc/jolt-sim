(ns jolt.sim.fixtures.outbox-delivery
  "Ordinary Jolt whole-application fixture for one committed outbox delivery.

  The same function runs against real native resources or underneath jolt-sim
  handlers. This namespace itself has no simulator dependency: it uses public
  jolt-http, jdbc.core, teensyp TCP/client, jolt.bytes, and jolt.bencode APIs,
  plus the existing ordinary HTTP and framed-TCP fixture seams.

  One HTTP POST carries a bencoded canonical command whose payload is an octet
  vector. The request handler applies the existing SQLite outbox transition and
  returns after COMMIT. Once the HTTP server is quiescent, the outer application
  reloads the pending row, closes SQLite, and gives the materialized message to
  an ordinary framed TCP/bencode delivery function. The receiver returns an
  outbox-id/attempt ack. This first witness deliberately does not mark the row
  delivered: the reloaded store state remains :pending after the acknowledged
  attempt. It proves post-COMMIT visibility, not close/reopen or crash
  durability."
  (:require [jdbc.core :as jdbc]
            [jolt.bencode :as bencode]
            [jolt.bytes :as bytes]
            [jolt.example.outbox.sqlite :as store]
            [jolt.host :as host]
            [jolt.http.body :as http-body]
            [jolt.sim.fixtures.http-sqlite :as http-fixture]
            [jolt.sim.fixtures.tcp-bencode :as framed]
            [teensyp.client :as client]
            [teensyp.server :as tcp]))

(def default-command
  {:request-id "req-1"
   :entity-id "entity-a"
   :payload [0 127 128 255]})

(def ^:private command-keys
  #{:request-id :entity-id :payload})

(def ^:private command-wire-keys
  #{"request-id" "entity-id" "payload"})

(def ^:private delivery-wire-keys
  #{"type" "outbox-id" "request-id" "entity-id" "version" "payload"
    "attempt"})

(def ^:private bencode-content-type "application/x-bencode")

(defn- fail! [reason detail]
  (throw
   (ex-info
    (str "outbox-delivery fixture: " (name reason))
    {:type :jolt.sim.fixtures.outbox-delivery/invalid-flow
     :reason reason
     :detail detail})))

(defn- stable-error-summary [error]
  {:class (str (class error))
   :message (or (ex-message error)
                (try (host/condition-message error)
                     (catch :default _ nil)))
   :data (when-let [data (ex-data error)]
           (into {}
                 (map (fn [[k v]] [(str k) (pr-str v)]))
                 data))})

(defn- cleanup-attempt [operation thunk]
  (try
    (thunk)
    nil
    (catch :default error
      {:operation operation :error error})))

(defn- cleanup-summaries [cleanup-errors]
  (mapv (fn [{:keys [operation error]}]
          (assoc (stable-error-summary error) :operation operation))
        cleanup-errors))

(defn- throw-with-cleanup! [primary cleanup-errors]
  (if primary
    (if (seq cleanup-errors)
      (throw
       (ex-info
        (or (ex-message primary) (str primary))
        (assoc (or (ex-data primary) {})
               :outbox-delivery/primary-error (stable-error-summary primary)
               :outbox-delivery/cleanup-errors
               (cleanup-summaries cleanup-errors))
        primary))
      (throw primary))
    (when (seq cleanup-errors)
      (let [first-error (:error (first cleanup-errors))]
        (throw
         (ex-info
          "outbox-delivery fixture cleanup failed"
          {:type :jolt.sim.fixtures.outbox-delivery/cleanup-failure
           :outbox-delivery/cleanup-errors
           (cleanup-summaries cleanup-errors)}
          first-error))))))

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (do
          (System/arraycopy chunk 0 out offset (alength chunk))
          (recur (rest remaining) (+ offset (alength chunk))))
        out))))

(defn- decode-bencode-exact [context ^bytes raw]
  (let [decoded (bencode/decode-bytes raw)]
    (when-not (= :ok (:status decoded))
      (fail! :invalid-bencode
             {:context context
              :raw-length (alength raw)
              :raw-bytes (vec raw)
              :status (:status decoded)
              :reason (:reason decoded)}))
    (let [position (bytes/cursor-position (:cursor decoded))]
      (when-not (= (alength raw) position)
        (fail! :trailing-bencode
               {:context context
                :length (alength raw)
                :position position})))
    (:value decoded)))

(defn- command->wire [command]
  (when-not (and (map? command)
                 (= command-keys (set (keys command))))
    (fail! :invalid-command {:value command}))
  {"request-id" (:request-id command)
   "entity-id" (:entity-id command)
   "payload" (:payload command)})

(defn- wire->command [value]
  (when-not (and (map? value)
                 (= command-wire-keys (set (keys value))))
    (fail! :invalid-command-wire {:value value}))
  {:request-id (get value "request-id")
   :entity-id (get value "entity-id")
   :payload (get value "payload")})

(defn- post-request-bytes [command host port]
  (let [body (bencode/encode (command->wire command))
        head (.getBytes
              (str "POST /commands HTTP/1.1\r\n"
                   "Host: " host ":" port "\r\n"
                   "Content-Type: " bencode-content-type "\r\n"
                   "Content-Length: " (alength body) "\r\n"
                   "Connection: close\r\n"
                   "\r\n")
              "UTF-8")]
    (concat-byte-arrays [head body])))

(defn- command-response-wire [result]
  {"type" "command_ok"
   "status" (name (:status result))
   "request-id" (:request-id result)
   "entity-id" (:entity-id result)
   "version" (:version result)
   "outbox-id" (:outbox-id result)})

(defn- semantic-identities [result]
  (let [request-id (:request-id result)
        outbox-id (:outbox-id result)]
    {:request-id request-id
     :transaction-id [:outbox/command request-id]
     :outbox-id outbox-id
     :delivery-id [:outbox/delivery outbox-id]
     :attempt-id [:outbox/delivery-attempt outbox-id 1]}))

(defn- row->delivery-wire [row]
  {"type" "outbox_delivery"
   "outbox-id" (:outbox-id row)
   "request-id" (:request-id row)
   "entity-id" (:entity-id row)
   "version" (:version row)
   "payload" (:payload row)
   "attempt" 1})

(defn- expected-ack [message]
  {"type" "outbox_delivery_ok"
   "outbox-id" (get message "outbox-id")
   "attempt" (get message "attempt")})

(defn- receiver-reply [received]
  (fn [message]
    (when-not (and (map? message)
                   (= delivery-wire-keys (set (keys message)))
                   (= "outbox_delivery" (get message "type")))
      (fail! :invalid-delivery-wire {:value message}))
    (swap! received conj message)
    (expected-ack message)))

(defn- exchange-deliveries! [host port messages]
  (let [connection* (atom nil)
        body
        (try
          {:value
           (let [connection (client/connect host port
                                            {:connect-timeout-ms 5000})
                 _ (reset! connection* connection)
                 exchange (framed/exchange! connection messages)
                 first-close (client/close! connection)
                 second-close (client/close! connection)]
             (assoc exchange
                    :close-results
                    {:connection [first-close second-close]}))}
          (catch :default error
            {:error error}))
        cleanup-errors
        (vec
         (keep identity
               [(when-let [connection @connection*]
                  (cleanup-attempt :delivery-connection-close
                                   #(client/close! connection)))]))]
    (throw-with-cleanup! (:error body) cleanup-errors)
    (:value body)))

(defn- load-pending-delivery [conn]
  ;; apply-command! has already returned across COMMIT. Reload through the
  ;; ordinary adapter so the worker consumes the committed row, not :emitted.
  (let [state (store/load-state conn)
        rows (filterv #(= :pending (:status %)) (:outbox state))
        _ (when-not (= 1 (count rows))
            (fail! :unexpected-pending-count {:count (count rows)}))
        messages (mapv row->delivery-wire rows)]
    {:state state
     :messages messages}))

(defn- deliver-messages! [host port messages]
  (let [exchange (exchange-deliveries! host port messages)
        expected (mapv expected-ack messages)]
    (when-not (= expected (:replies exchange))
      (fail! :ack-mismatch
             {:expected expected :actual (:replies exchange)}))
    exchange))

(defn- command-handler [conn command-evidence]
  (fn [request]
    (when-not (= [:post "/commands"]
                 [(:request-method request) (:uri request)])
      (fail! :unsupported-http-route
             {:request-method (:request-method request)
              :uri (:uri request)}))
    (let [command (->> (http-body/body-bytes (:body request))
                       (decode-bencode-exact :http-command)
                       wire->command)
          step (store/apply-command! conn command)
          result (:result step)
          evidence
          {:identities (semantic-identities result)
           :command {:value command
                     :result result
                     :emitted (:emitted step)}
           ;; Retained only until the ordinary post-COMMIT reload is compared.
           ;; The final public evidence uses :store-state instead.
           :committed-state (:state step)}]
      (reset! command-evidence evidence)
      {:status 200
       :headers {"Content-Type" bencode-content-type}
       :body (bencode/encode (command-response-wire result))})))

(defn exercise-outbox-delivery
  "Runs one ordinary HTTP -> committed SQLite outbox -> framed TCP/bencode
  delivery/ack flow. Returns canonical immutable evidence with no native
  handles, pointers, byte arrays, mutable values, controller objects, or
  ephemeral ports. The optional command arity is intended for later real-mode
  characterization; the hermetic plan for this first slice pins default-command.
  The bencode payload is an octet vector; SQLite stores the same semantics as a
  BLOB, but this witness does not claim bencode binary-string wire parity."
  ([]
   (exercise-outbox-delivery default-command))
  ([command]
   (let [receiver-errors (atom [])
         received (atom [])
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler (receiver-reply received))
          :error-logger
          #(swap! receiver-errors conj (stable-error-summary %)))
         command-evidence* (atom nil)
         body
         (try
           {:value
            (let [{:keys [http-cycle command-evidence store-state messages]}
                  (with-open [conn (jdbc/connection "sqlite::memory:")]
                    (store/init-schema! conn)
                    (let [http-cycle
                          (http-fixture/run-request-cycle
                           (command-handler conn command-evidence*)
                           (fn [host port]
                             (post-request-bytes command host port)))
                          command-evidence @command-evidence*
                          _ (when-not command-evidence
                              (fail! :missing-application-evidence {}))
                          pending (load-pending-delivery conn)
                          store-state (:state pending)
                          _ (when-not (= (:committed-state command-evidence)
                                         store-state)
                              (fail! :committed-state-mismatch
                                     {:step-state
                                      (:committed-state command-evidence)
                                      :loaded-state store-state}))]
                      {:http-cycle http-cycle
                       :command-evidence command-evidence
                       :store-state store-state
                       :messages (:messages pending)}))
                  ;; The HTTP request has succeeded, its server is quiescent,
                  ;; and SQLite is closed before downstream delivery begins.
                  delivery (deliver-messages! "127.0.0.1" (:port receiver)
                                               messages)
                  app (-> command-evidence
                          (dissoc :committed-state)
                          (assoc :store-state store-state
                                 :delivery delivery))
                  parsed (:parsed http-cycle)
                  response
                  (decode-bencode-exact :http-response (:body parsed))]
              {:application app
               :http {:status (:status parsed)
                      :content-type
                      (get (:headers parsed) "content-type")
                      :content-length
                      (get (:headers parsed) "content-length")
                      :response response
                      :server-errors (:server-errors http-cycle)
                      :close-results (:close-results http-cycle)}})}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep identity
                [(cleanup-attempt :receiver-stop #(tcp/stop-server receiver))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     ;; stop-server quiesces the receiver before its final error snapshot.
     (assoc (:value body)
            :receiver {:requests @received
                       :server-errors @receiver-errors}))))
