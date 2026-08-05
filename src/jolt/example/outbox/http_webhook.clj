(ns jolt.example.outbox.http-webhook
  "Ordinary JSON-over-HTTP delivery for one pending durable outbox row.

  This namespace has no simulator dependency. It uses the public
  jolt.http-client API, clojure.data.json, and the existing durable SQLite
  adapter. A simulator-enabled compiler may intercept the native socket and
  SQLite calls below their public APIs; the application code is unchanged.

  Delivery is deliberately fail closed. The request carries the row's exact
  durable identity plus a caller-owned attempt id. A row is marked delivered
  only after a 2xx response, an exact single JSON value, and an acknowledgement
  whose closed shape equals the expected attempt and durable identity."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.example.outbox.sqlite :as store]
            [jolt.http-client :as http]))

(def ^:private row-keys
  #{:outbox-id :request-id :entity-id :version :payload :status})

(def ^:private durable-wire-keys
  #{"outbox-id" "request-id" "entity-id" "version"})

(def ^:private ack-wire-keys
  #{"type" "durable" "attempt-id"})

(defn- fail! [reason detail]
  (throw
   (ex-info
    (str "outbox HTTP webhook: " (name reason))
    {:type :jolt.example.outbox.http-webhook/delivery-refused
     :reason reason
     :detail detail})))

(defn- positive-integer? [value]
  (and (integer? value) (<= 1 value)))

(defn- nonempty-string? [value]
  (and (string? value) (not (empty? value))))

(defn- octet? [value]
  (and (integer? value) (<= 0 value) (<= value 255)))

(defn- check-row! [row]
  (when-not (and (map? row)
                 (= row-keys (set (keys row)))
                 (= :pending (:status row))
                 (positive-integer? (:outbox-id row))
                 (nonempty-string? (:request-id row))
                 (nonempty-string? (:entity-id row))
                 (positive-integer? (:version row))
                 (vector? (:payload row))
                 (every? octet? (:payload row)))
    (fail! :invalid-pending-row {:value row}))
  row)

(defn durable-identity
  "Returns the exact JSON-domain identity correlated by the webhook ack."
  [row]
  (check-row! row)
  {"outbox-id" (:outbox-id row)
   "request-id" (:request-id row)
   "entity-id" (:entity-id row)
   "version" (:version row)})

(defn delivery-request
  "Builds the closed request value for a pending row and caller attempt id."
  [row attempt-id]
  (when-not (nonempty-string? attempt-id)
    (fail! :invalid-attempt-id {:value attempt-id}))
  {"type" "outbox_delivery"
   "durable" (durable-identity row)
   "attempt-id" attempt-id
   "payload" (:payload row)})

(defn expected-ack
  "Builds the only acknowledgement that can authorize durable marking."
  [row attempt-id]
  (when-not (nonempty-string? attempt-id)
    (fail! :invalid-attempt-id {:value attempt-id}))
  {"type" "outbox_delivery_ok"
   "durable" (durable-identity row)
   "attempt-id" attempt-id})

(defn- json-media-type? [value]
  (and (string? value)
       (= "application/json"
          (-> value
              (str/split #";" 2)
              first
              str/trim
              str/lower-case))))

(defn- json-whitespace? [c]
  ;; RFC 8259 permits exactly these four characters around a JSON value.
  ;; clojure.string/trim is intentionally broader.
  (or (= c \space)
      (= c \tab)
      (= c \return)
      (= c \newline)))

(defn- trim-json-whitespace [text]
  (let [length (count text)
        start (loop [index 0]
                (if (and (< index length)
                         (json-whitespace? (nth text index)))
                  (recur (inc index))
                  index))
        end (loop [index length]
              (if (and (> index start)
                       (json-whitespace? (nth text (dec index))))
                (recur (dec index))
                index))]
    (subs text start end)))

(defn- parse-json-exact [body]
  (when-not (string? body)
    (fail! :invalid-response-body {:class (str (class body))}))
  (try
    (json/read-str (trim-json-whitespace body)
                   :extra-data-fn json/on-extra-throw)
    (catch :default error
      (fail! :invalid-json
             {:message (ex-message error)
              :body-length (count body)}))))

(defn- check-ack! [ack expected]
  (when-not (and (map? ack)
                 (= ack-wire-keys (set (keys ack)))
                 (map? (get ack "durable"))
                 (= durable-wire-keys
                    (set (keys (get ack "durable"))))
                 (= expected ack))
    (fail! :ack-mismatch {:expected expected :actual ack}))
  ack)

(defn deliver-pending!
  "POSTs one pending row to `url` and marks it delivered only after an exact
  correlated JSON acknowledgement.

  `attempt-id` is a non-empty caller-owned string. The request is a closed JSON
  object containing `type`, `durable`, `attempt-id`, and `payload`. The
  acknowledgement must be exactly the closed value returned by expected-ack.
  Non-2xx status, non-JSON media type, malformed or trailing JSON data, and any
  correlation mismatch throw before store/mark-delivered! is called.

  Returns bounded request/response/marking evidence; native resources remain
  owned and retired by jolt.http-client and the supplied SQLite connection."
  [conn url row attempt-id]
  (when-not (nonempty-string? url)
    (fail! :invalid-url {:value url}))
  (let [request (delivery-request row attempt-id)
        expected (expected-ack row attempt-id)
        response (http/post url
                            {:body (json/write-str request)
                             :content-type "application/json"
                             :accept :json
                             :throw-exceptions false})
        status (:status response)]
    (when-not (and (integer? status) (<= 200 status 299))
      (fail! :non-success-status {:status status}))
    (let [content-type (get-in response [:headers "content-type"])]
      (when-not (json-media-type? content-type)
        (fail! :non-json-response {:content-type content-type})))
    (let [ack (check-ack! (parse-json-exact (:body response)) expected)
          mark-step (store/mark-delivered! conn (:outbox-id row))]
      (when-not (and (true? (:changed? mark-step))
                     (= (assoc row :status :delivered) (:row mark-step)))
        (fail! :durable-mark-mismatch
               {:expected-row (assoc row :status :delivered)
                :marking (dissoc mark-step :state)}))
      {:request request
       :response {:status status
                  :content-type (get-in response [:headers "content-type"])
                  :ack ack}
       :marking (dissoc mark-step :state)})))
