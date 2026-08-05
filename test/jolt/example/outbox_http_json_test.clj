(ns jolt.example.outbox-http-json-test
  "Focused tests for jolt.example.outbox.http-json over real in-memory
  SQLite. The handler is invoked directly with fabricated jolt-http request
  maps -- a reified public jolt.http.body/RequestBody for :body -- so no
  sockets, reactor, or server lifecycle participate; the durable side is the
  real jdbc.core SQLite transaction path exercised by
  jolt.example.outbox-sqlite-test. Every test owns exactly one connection to
  a fresh :memory: database; tests run sequentially. The physical table
  names below are the adapter's durable storage contract, pinned exactly as
  in jolt.example.outbox-sqlite-test."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [jolt.example.outbox.http-json :as facade]
            [jolt.example.outbox.sqlite :as store]
            [jolt.http.body :as http-body]))

;; ---- harness ----------------------------------------------------------------

(def ^:private entities-table "outbox_example_entities")
(def ^:private requests-table "outbox_example_requests")
(def ^:private outbox-table "outbox_example_outbox")

(defn- fresh-conn []
  (jdbc/connection "sqlite::memory:"))

(defn- table-count [conn table]
  (:n (jdbc/fetch-one conn (str "select count(*) as n from " table))))

(defn- table-counts [conn]
  {:entities (table-count conn entities-table)
   :requests (table-count conn requests-table)
   :outbox (table-count conn outbox-table)})

(defn- concat-chunks
  [chunks]
  (let [length (reduce + 0 (map alength chunks))
        output (byte-array length)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (let [chunk-length (alength chunk)]
          (dotimes [index chunk-length]
            (aset output (+ offset index) (aget chunk index)))
          (recur (rest remaining) (+ offset chunk-length)))
        output))))

(defn- tracked-request-body
  "A realistic finite streaming RequestBody. Returns the body plus an atom
   counting body-recv calls, including the final EOF read. body-bytes drains
   through body-recv exactly like jolt-http's ChanRequestBody."
  [chunks]
  (let [remaining (atom (vec chunks))
        recv-count (atom 0)
        body
        (reify http-body/RequestBody
          (body-recv [_]
            (swap! recv-count inc)
            (let [chunk (first @remaining)]
              (swap! remaining #(if (seq %) (subvec % 1) %))
              chunk))
          (body-bytes [this]
            (loop [values []]
              (if-let [chunk (http-body/body-recv this)]
                (recur (conj values chunk))
                (concat-chunks values))))
          (body-string [this charset]
            (String. (http-body/body-bytes this)
                     ^String (or charset "UTF-8"))))]
    {:body body :recv-count recv-count :remaining remaining}))

(defn- request-body-bytes
  [^bytes bs]
  (:body (tracked-request-body [bs])))

(defn- request-body-of
  "A fabricated request :body through the public jolt.http.body/RequestBody
  protocol -- the same seam a real jolt-http server hands the handler."
  [^String s]
  (request-body-bytes (.getBytes s "UTF-8")))

(defn- request
  "Builds the exact jolt-http request map shape: keyword :request-method, raw
  string :uri, lower-case string-keyed :headers, RequestBody :body. A nil
  content-type omits the header entirely."
  [method uri content-type body]
  {:request-method method
   :uri uri
   :headers (if (some? content-type) {"content-type" content-type} {})
   :body (request-body-of (or body ""))})

(defn- request-with-body
  [method uri content-type body]
  {:request-method method
   :uri uri
   :headers (if (some? content-type) {"content-type" content-type} {})
   :body body})

(defn- byte-slice
  [^bytes input start end]
  (let [output (byte-array (- end start))]
    (dotimes [index (- end start)]
      (aset output index (aget input (+ start index))))
    output))

(defn- json-command
  "The canonical happy-path request for `entity-id`/`request-id` with a JSON
  payload array."
  [entity-id request-id payload]
  (request :post
           (str "/v1/entities/" entity-id "/commands/" request-id)
           "application/json"
           (json/write-str {"payload" payload})))

(defn- parse-body [response]
  (json/read-str (:body response)))

(defn- committed-wire [request-id entity-id version outbox-id]
  {"status" "committed"
   "request-id" request-id
   "entity-id" entity-id
   "version" version
   "outbox-id" outbox-id})

(defn- error-wire [type reason]
  {"error" {"type" type "reason" reason}})

;; ---- accepted: fresh commit ---------------------------------------------------

(deftest fresh-commit-accepted-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)
          response (handler (json-command "entity-a" "req-1" [0 127 128 255]))]
      (testing "the wire response is the exact closed committed envelope"
        (is (= 201 (:status response)))
        (is (= "application/json" (get (:headers response) "Content-Type")))
        (is (= (committed-wire "req-1" "entity-a" 1 1)
               (parse-body response))))
      (testing "the durable transaction path committed exactly one of each row"
        (is (= {:entities 1 :requests 1 :outbox 1} (table-counts conn)))
        (is (= {:version 1 :payload [0 127 128 255]}
               (get-in (store/load-state conn) [:entities "entity-a"]))))
      (testing "an empty payload is accepted and survives as a zero-length BLOB"
        (let [empty (handler (json-command "entity-b" "req-2" []))]
          (is (= 201 (:status empty)))
          (is (= (committed-wire "req-2" "entity-b" 1 2)
                 (parse-body empty)))
          (is (= [] (get-in (store/load-state conn)
                            [:entities "entity-b" :payload])))
          (is (= {:entities 2 :requests 2 :outbox 2} (table-counts conn))))))))

;; ---- exact idempotent replay ----------------------------------------------------

(deftest exact-replay-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)
          first-response (handler (json-command "entity-a" "req-1" [7 7 7]))
          counts-before (table-counts conn)
          state-before (store/load-state conn)
          replay (handler (json-command "entity-a" "req-1" [7 7 7]))]
      (testing "the replay is 200 with a body byte-identical to the first 201"
        (is (= 201 (:status first-response)))
        (is (= 200 (:status replay)))
        (is (= (:body first-response) (:body replay)))
        (is (= (committed-wire "req-1" "entity-a" 1 1)
               (parse-body replay))))
      (testing "the replay mutated nothing"
        (is (= counts-before (table-counts conn)))
        (is (= state-before (store/load-state conn))))
      (testing "a third identical request is still a stable 200 replay"
        (let [third (handler (json-command "entity-a" "req-1" [7 7 7]))]
          (is (= 200 (:status third)))
          (is (= (:body first-response) (:body third)))
          (is (= counts-before (table-counts conn)))
          (is (= state-before (store/load-state conn))))))))

;; ---- conflicting request-id reuse: 409 without mutation -------------------------

(deftest request-id-conflict-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)
          accepted (handler (json-command "entity-a" "req-1" [1]))
          counts-before (table-counts conn)
          state-before (store/load-state conn)]
      (is (= 201 (:status accepted)))
      (testing "the same request-id with a different payload conflicts"
        (let [conflict (handler (json-command "entity-a" "req-1" [2]))]
          (is (= 409 (:status conflict)))
          (is (= "application/json" (get (:headers conflict) "Content-Type")))
          (is (= {"error" {"type" "request-id-conflict"
                           "reason" "request-id-conflict"
                           "request-id" "req-1"}}
                 (parse-body conflict)))))
      (testing "the same request-id under a different entity path conflicts"
        (let [conflict (handler (json-command "entity-b" "req-1" [1]))]
          (is (= 409 (:status conflict)))
          (is (= "request-id-conflict"
                 (get-in (parse-body conflict) ["error" "type"])))))
      (testing "every conflict left the database exactly unchanged"
        (is (= counts-before (table-counts conn)))
        (is (= state-before (store/load-state conn))))
      (testing "a fresh request-id still commits after the conflicts"
        (let [response (handler (json-command "entity-a" "req-2" [9]))]
          (is (= 201 (:status response)))
          (is (= (committed-wire "req-2" "entity-a" 2 2)
                 (parse-body response)))
          (is (= {:entities 1 :requests 2 :outbox 2} (table-counts conn))))))))

;; ---- route and method rejection ---------------------------------------------------

(deftest route-and-method-rejection-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)]
      (testing "paths that do not match the template get the closed 404 body"
        (doseq [uri ["/v1/entities"
                     "/v1/entities/e/commands"
                     "/v1/entities/e/commands/r/extra"
                     "/v1/entities//commands/r"
                     "/v1/entities/e/commands/"
                     "/commands"
                     "/"]]
          (let [response (handler (request :post uri
                                           "application/json"
                                           "{\"payload\":[1]}"))]
            (is (= 404 (:status response)) (str "uri " uri))
            (is (= (error-wire "not-found" "no-matching-route")
                   (parse-body response))
                (str "uri " uri)))))
      (testing "any non-POST method on the template gets the closed 405 body"
        (doseq [method [:get :put :delete :head]]
          (let [response (handler (request method
                                           "/v1/entities/e/commands/r"
                                           "application/json"
                                           "{\"payload\":[1]}"))]
            (is (= 405 (:status response)) (str "method " method))
            (is (= "POST" (get (:headers response) "Allow"))
                (str "method " method))
            (is (= (error-wire "method-not-allowed" "post-required")
                   (parse-body response))
                (str "method " method)))))
      (testing "no rejection touched the database"
        (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn)))))))

;; ---- media type rejection ---------------------------------------------------------

(deftest media-type-rejection-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)]
      (testing "a missing Content-Type is rejected 415"
        (let [response (handler (request :post
                                         "/v1/entities/e/commands/r"
                                         nil "{\"payload\":[1]}"))]
          (is (= 415 (:status response)))
          (is (= (error-wire "unsupported-media-type" "json-required")
                 (parse-body response)))))
      (testing "non-JSON media types are rejected 415"
        (doseq [content-type ["text/plain"
                              "application/octet-stream"
                              "application/x-bencode"
                              "application/jsonx"]]
          (let [response (handler (request :post
                                           "/v1/entities/e/commands/r"
                                           content-type "{\"payload\":[1]}"))]
            (is (= 415 (:status response)) (str "content-type " content-type))
            (is (= (error-wire "unsupported-media-type" "json-required")
                   (parse-body response))
                (str "content-type " content-type)))))
      (testing "no rejection reached the database"
        (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn))))
      (testing "media-type parameters, case, and padding are accepted"
        (doseq [[content-type request-id]
                [["application/json; charset=utf-8" "r1"]
                 ["Application/JSON" "r2"]
                 [" application/json " "r3"]]]
          (let [response (handler (request :post
                                           (str "/v1/entities/e/commands/"
                                                request-id)
                                           content-type "{\"payload\":[1]}"))]
            (is (= 201 (:status response))
                (str "content-type " content-type))))
        (is (= {:entities 1 :requests 3 :outbox 3} (table-counts conn)))))))

;; ---- malformed JSON ----------------------------------------------------------------

(deftest body-limit-and-transport-ownership-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [exact-text "{\"payload\":[]}"
          over-text "{\"payload\":[0]}"
          ^bytes exact-bytes (.getBytes exact-text "UTF-8")
          ^bytes over-bytes (.getBytes over-text "UTF-8")
          limit (alength exact-bytes)
          handler (facade/command-handler conn {:max-body-bytes limit})]
      (testing "the exact byte limit is accepted"
        (let [response (handler (request :post
                                         "/v1/entities/e/commands/exact"
                                         "application/json"
                                         exact-text))]
          (is (= 201 (:status response)))))
      (testing "one byte over is 413, drains the finite stream, and mutates nothing"
        (let [counts-before (table-counts conn)
              state-before (store/load-state conn)
              {:keys [body recv-count remaining]}
              (tracked-request-body
               [(byte-slice over-bytes 0 limit)
                (byte-slice over-bytes limit (alength over-bytes))])
              response
              (handler
               (request-with-body :post
                                  "/v1/entities/e/commands/over"
                                  "application/json"
                                  body))]
          (is (= 413 (:status response)))
          (is (= (error-wire "content-too-large" "body-too-large")
                 (parse-body response)))
          (is (= 3 @recv-count) "two chunks plus EOF were consumed")
          (is (empty? @remaining))
          (is (= counts-before (table-counts conn)))
          (is (= state-before (store/load-state conn)))))
      (testing "data after the crossing chunk is also drained"
        (let [{:keys [body recv-count remaining]}
              (tracked-request-body
               [(byte-slice over-bytes 0 limit)
                (byte-slice over-bytes limit (alength over-bytes))
                (byte-array [9 8 7])])
              response
              (handler
               (request-with-body :post
                                  "/v1/entities/e/commands/trailing"
                                  "application/json"
                                  body))]
          (is (= 413 (:status response)))
          (is (= 4 @recv-count)
              "crossing and trailing chunks plus EOF were consumed")
          (is (empty? @remaining))))
      (testing "a RequestBody transport exception remains server-owned"
        (let [sentinel (ex-info "body transport failed" {:sentinel true})
              calls (atom 0)
              body
              (reify http-body/RequestBody
                (body-recv [_]
                  (swap! calls inc)
                  (throw sentinel))
                (body-bytes [_] (throw sentinel))
                (body-string [_ _] (throw sentinel)))
              counts-before (table-counts conn)
              state-before (store/load-state conn)
              caught
              (try
                (handler
                 (request-with-body :post
                                    "/v1/entities/e/commands/transport"
                                    "application/json"
                                    body))
                nil
                (catch :default e
                  e))]
          (is (identical? sentinel caught))
          (is (= 1 @calls) "the failed stream was not drained a second time")
          (is (= counts-before (table-counts conn)))
          (is (= state-before (store/load-state conn)))))
      (testing "invalid limits fail at handler construction"
        (doseq [value [nil 0 -1 1.5 "64"]]
          (let [caught
                (try
                  (facade/command-handler conn {:max-body-bytes value})
                  nil
                  (catch :default e
                    e))]
            (is (= :jolt.example.outbox.http-json/invalid-config
                   (:type (ex-data caught)))
                (str "value " (pr-str value)))))))))

(deftest invalid-json-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)
          post (fn [body]
                 (handler (request :post "/v1/entities/e/commands/r"
                                   "application/json" body)))]
      (testing "unparseable and trailing-data bodies get closed 400 invalid-json"
        (doseq [body [""
                      "   "
                      "{"
                      "{\"payload\": [1,}"
                      "hello"
                      "[1,2"
                      "{\"payload\":[1]} trailing"
                      "{\"payload\":[1]} {\"payload\":[2]}"]]
          (let [response (post body)]
            (is (= 400 (:status response)) (str "body " (pr-str body)))
            (is (= (error-wire "bad-request" "invalid-json")
                   (parse-body response))
                (str "body " (pr-str body))))))
      (testing "surrounding JSON whitespace is accepted"
        (let [response (post " \t\r\n{\"payload\": [1]}\r\n\t ")]
          (is (= 201 (:status response)))))
      (testing "Unicode whitespace outside RFC 8259 is rejected"
        (doseq [body [(str "\u3000" "{\"payload\":[2]}")
                      (str "{\"payload\":[2]}" "\u3000")]]
          (let [response (post body)]
            (is (= 400 (:status response)))
            (is (= (error-wire "bad-request" "invalid-json")
                   (parse-body response))))))
      (testing "only the accepted body committed"
        (is (= {:entities 1 :requests 1 :outbox 1} (table-counts conn)))))))

(deftest malformed-utf8-is-invalid-json-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)
          response
          (handler
           (request-with-body :post
                              "/v1/entities/e/commands/malformed-utf8"
                              "application/json"
                              (request-body-bytes (byte-array [195 40]))))]
      (is (= 400 (:status response)))
      (is (= (error-wire "bad-request" "invalid-json")
             (parse-body response)))
      (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn))))))

;; ---- parsed but non-canonical command bodies -----------------------------------------

(deftest invalid-command-body-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)
          post (fn [body]
                 (handler (request :post "/v1/entities/e/commands/r"
                                   "application/json" body)))]
      (testing "parsed JSON values of any other shape get closed 400 bodies"
        (doseq [body ["{}"
                      "{\"payload\":[1],\"extra\":true}"
                      "{\"payload\":\"abc\"}"
                      "{\"payload\":[1.5]}"
                      "{\"payload\":[1e2]}"
                      "{\"payload\":[-1]}"
                      "{\"payload\":[256]}"
                      "{\"payload\":[null]}"
                      "{\"payload\":[true]}"
                      "{\"payload\":[[1]]}"
                      "{\"payload\":{\"0\":1}}"
                      "{\"payload\":null}"
                      "[1]"
                      "\"payload\""
                      "42"
                      "null"]]
          (let [response (post body)]
            (is (= 400 (:status response)) (str "body " body))
            (is (= (error-wire "bad-request" "invalid-command-body")
                   (parse-body response))
                (str "body " body)))))
      (testing "the octet boundaries 0 and 255 are accepted"
        (let [response (post "{\"payload\":[0,255]}")]
          (is (= 201 (:status response)))
          (is (= (committed-wire "r" "e" 1 1) (parse-body response)))))
      (testing "only the accepted body committed"
        (is (= {:entities 1 :requests 1 :outbox 1} (table-counts conn)))
        (is (= [0 255] (get-in (store/load-state conn) [:entities "e" :payload])))))))

;; ---- path parameter semantics ---------------------------------------------------------

(deftest path-param-semantics-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)]
      (testing "wild segments are percent-decoded UTF-8 by the router shim"
        (let [response
              (handler (request :post
                                "/v1/entities/entity%20a/commands/req%2F1"
                                "application/json"
                                "{\"payload\":[5]}"))]
          (is (= 201 (:status response)))
          (is (= (committed-wire "req/1" "entity a" 1 1)
                 (parse-body response)))
          (is (= {:version 1 :payload [5]}
                 (get-in (store/load-state conn) [:entities "entity a"])))))
      (testing "a plus sign is data, never a space"
        (let [response
              (handler (request :post
                                "/v1/entities/entity+b/commands/req+2"
                                "application/json"
                                "{\"payload\":[6]}"))]
          (is (= 201 (:status response)))
          (is (= (committed-wire "req+2" "entity+b" 1 2)
                 (parse-body response)))))
      (testing "a decoded embedded NUL is rejected before storage"
        (let [response
              (handler (request :post
                                "/v1/entities/entity%00c/commands/req-3"
                                "application/json"
                                "{\"payload\":[7]}"))]
          (is (= 400 (:status response)))
          (is (= (error-wire "bad-request" "unstorable-id")
                 (parse-body response)))))
      (testing "a legitimately encoded replacement character remains data"
        (let [response
              (handler (request :post
                                "/v1/entities/entity-d/commands/%EF%BF%BD"
                                "application/json"
                                "{\"payload\":[8]}"))]
          (is (= 201 (:status response)))
          (is (= (committed-wire "\uFFFD" "entity-d" 1 3)
                 (parse-body response)))))
      (testing "malformed path escapes are closed client errors"
        (doseq [uri ["/v1/entities/e/commands/%"
                     "/v1/entities/e/commands/%GG"
                     "/v1/entities/e/commands/%80"
                     "/v1/entities/e/commands/%C0%AF"
                     "/v1/entities/e/commands/%C3%28"
                     "/v1/entities/e/commands/%E0%80%AF"
                     "/v1/entities/e/commands/%E2%82"
                     "/v1/entities/e/commands/%ED%A0%80"
                     "/v1/entities/e/commands/%F0%80%80%AF"
                     "/v1/entities/e/commands/%F0%9F%92"
                     "/v1/entities/e/commands/%F4%90%80%80"
                     "/v1/entities/e/commands/%F5%80%80%80"]]
          (let [response
                (handler (request :post uri "application/json"
                                  "{\"payload\":[8]}"))]
            (is (= 400 (:status response)) (str "uri " uri))
            (is (= (error-wire "bad-request" "invalid-path-encoding")
                   (parse-body response))
                (str "uri " uri)))))
      (testing "only the three storable commands committed"
        (is (= {:entities 3 :requests 3 :outbox 3} (table-counts conn)))
        (is (nil? (get-in (store/load-state conn) [:request-log "req-3"])))))))

(deftest rejected-streaming-bodies-are-drained-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [handler (facade/command-handler conn)
          cases [{:label "not found"
                  :method :post
                  :uri "/missing"
                  :content-type "application/json"
                  :status 404}
                 {:label "wrong method"
                  :method :put
                  :uri "/v1/entities/e/commands/r"
                  :content-type "application/json"
                  :status 405}
                 {:label "wrong media type"
                  :method :post
                  :uri "/v1/entities/e/commands/r"
                  :content-type "text/plain"
                  :status 415}
                 {:label "malformed path"
                  :method :post
                  :uri "/v1/entities/e/commands/%GG"
                  :content-type "application/json"
                  :status 400}]]
      (doseq [{:keys [label method uri content-type status]} cases]
        (testing label
          (let [{:keys [body recv-count remaining]}
                (tracked-request-body [(byte-array [1 2])
                                       (byte-array [3])
                                       (byte-array [4 5 6])])
                response
                (handler (request-with-body method uri content-type body))]
            (is (= status (:status response)))
            (is (= 4 @recv-count)
                "all three chunks plus EOF were consumed")
            (is (empty? @remaining)))))
      (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn))))))
