(ns jolt.example.outbox.http-json
  "Ordinary, simulator-independent JSON HTTP facade in front of the durable
  jolt.example.outbox.sqlite transaction path.

  This slice owns exactly one route and one transition:

      POST /v1/entities/:entity-id/commands/:request-id

  It is ordinary application code in the same sense as
  jolt.example.outbox.sqlite. It knows only the public jolt-http synchronous
  handler contract (a request map with a keyword :request-method, a raw
  string :uri, lower-case string-keyed :headers, and a
  jolt.http.body/RequestBody :body), public clojure.data.json, the public
  Reitit router shim (reitit.trie-jolt registers the reitit.Trie mirror so
  unmodified reitit-core routes on jolt), and the public
  jolt.example.outbox.sqlite API. It has no atoms, threads, sockets, clocks,
  entropy, host identity, or simulator dependency: the caller owns the SQLite
  connection, schema initialization, and any jolt.http.server lifecycle.
  Whether the process's native socket and SQLite foreign calls reach the host
  or a simulated FFI world remains the caller's choice, exactly as for the
  existing bencode fixture lanes.

  Exact wire semantics:

  Route matching is reitit-core matching over the raw :uri. The two wild
  path segments are reitit parameters: each is a non-empty path segment (an
  empty segment never matches, so both identifiers are inherently non-empty
  strings) and is percent-decoded by the shim's Trie mirror as UTF-8 -- a
  literal percent must arrive as %25, and a plus sign is data, never a
  space. A decoded identifier is taken literally (a decoded %2F is part of
  the identifier, not a separator). Malformed percent escapes or malformed
  encoded UTF-8 are rejected 400 before routing. A request whose path
  matches the template with any other method is a method error; any other
  path is not found.

  Requests:

  - Method must be POST. Any other method on the matched template is
    rejected 405 with an Allow: POST response header.
  - Content-Type must carry the application/json media type
    (case-insensitive; optional ; parameters are accepted and ignored --
    the body is always decoded as UTF-8 per RFC 8259). A missing or
    different media type is rejected 415.
  - The body is exactly one JSON value: a closed object with exactly the
    key \"payload\", whose value is an array of integers 0 through 255.
    Surrounding JSON whitespace is allowed, narrowly the four RFC 8259
    characters space, tab, carriage return, and line feed; any other data
    around or after the single value is rejected. Duplicate object keys keep the last
    occurrence (data.json semantics). Unparseable input is rejected 400
    invalid-json; a parsed value of any other shape is rejected 400
    invalid-command-body. The handler reads the stream incrementally and
    rejects a body larger than its configurable byte limit without retaining
    the excess.

  The derived canonical command is

      {:request-id <decoded request-id segment>
       :entity-id  <decoded entity-id segment>
       :payload    <body payload octet vector>}

  which is applied through jolt.example.outbox.sqlite/apply-command! -- the
  existing durable transaction path. The facade constructs only closed
  canonical commands and never repairs input.

  Responses (all bodies Content-Type: application/json, all bodies closed
  objects with exactly the keys shown):

  - 201 Created -- the command was newly committed (the step emitted
    exactly one pending outbox row). Body:

      {\"status\":\"committed\",\"request-id\":s,\"entity-id\":s,
       \"version\":n,\"outbox-id\":n}

  - 200 OK -- exact idempotent replay: the same request-id with an equal
    command returns the recorded result, with no database mutation and no
    new emission. The body is byte-identical to the original 201 body.

  - 409 Conflict -- the request-id was previously committed with a
    different command. The pure core throws before producing state, so the
    transaction performs no write and the database is exactly unchanged.
    Body:

      {\"error\":{\"type\":\"request-id-conflict\",
                  \"reason\":\"request-id-conflict\",
                  \"request-id\":s}}

  - 400 Bad Request -- malformed JSON, a non-closed command body, a payload
    element outside the octet range, malformed path encoding, or an
    identifier the adapter cannot store (for example an embedded NUL after
    percent-decoding). Body:

      {\"error\":{\"type\":\"bad-request\",
                  \"reason\":\"invalid-json\" |
                            \"invalid-command-body\" |
                            \"invalid-path-encoding\" |
                            \"unstorable-id\"}}

  - 404 Not Found -- no route matches :uri. Body:

      {\"error\":{\"type\":\"not-found\",\"reason\":\"no-matching-route\"}}

  - 405 Method Not Allowed -- the template matched but the method is not
    POST. Includes the Allow: POST header. Body:

      {\"error\":{\"type\":\"method-not-allowed\",\"reason\":\"post-required\"}}

  - 415 Unsupported Media Type -- Content-Type missing or not
    application/json. Body:

      {\"error\":{\"type\":\"unsupported-media-type\",\"reason\":\"json-required\"}}

  - 413 Content Too Large -- the body crossed the configured byte limit.
    Body:

      {\"error\":{\"type\":\"content-too-large\",\"reason\":\"body-too-large\"}}

  Checks run in exactly this order: route, method, media type, JSON syntax,
  body shape, then the durable transition. Only after every request check
  has passed does any database statement run, so a rejected request mutates
  nothing.

  Every other typed failure from the core or the adapter -- a corrupted
  durable state, an ambient-transaction violation, an unexpected write
  count, a driver error -- propagates unchanged to the server's error
  handler (by default a 500). The facade deliberately does not translate
  storage invariants into client semantics.

  Every response path consumes the request body through the public
  RequestBody protocol. This is operationally significant: jolt-http feeds
  streaming bodies through a bounded channel, so returning a 404, 405, or
  415 without draining could leave the parser blocked and strand the
  connection.

  The default body limit is 65536 bytes and can be replaced through
  command-handler's :max-body-bytes option. The limit bounds retained request
  memory. A peer that never finishes sending still requires the HTTP server's
  request deadline/abort policy; RequestBody currently exposes only recv, not
  cancellation or connection close.

  Concurrency: none claimed. The facade inherits the adapter's sequential,
  single-owner connection contract; the caller must serialize requests per
  connection (the existing lanes issue one request at a time). This slice
  defines the command route only; delivery, acknowledgement, and durable
  marking remain with the existing flows."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.example.outbox.sqlite :as store]
            [jolt.http.body :as http-body]
            [reitit.trie-jolt]
            [reitit.core :as r]))

;; ---- route ----------------------------------------------------------------

(def ^:private command-path
  "The one route this facade owns. Both wild segments are reitit path
   parameters named exactly as the canonical command fields."
  "/v1/entities/:entity-id/commands/:request-id")

(def ^:private command-router
  ;; Built once at load: router construction is pure, and requiring
  ;; reitit.trie-jolt above has already registered the reitit.Trie mirror.
  (r/router [[command-path {:post ::command}]]))

;; ---- typed facade failures --------------------------------------------------

(def ^:private invalid-request-type
  :jolt.example.outbox.http-json/invalid-request)

(defn- fail-request!
  "Rejects one request with the exact status and closed wire error body for
   `reason`, before any database statement runs. Carried as a typed ex-info
   so the handler's single catching boundary can distinguish deliberate
   rejections from storage and runtime failures; never escapes the handler."
  [http-status wire-type reason detail]
  (throw
   (ex-info
    (str "jolt.example.outbox.http-json: invalid request (" (name reason) ")")
    {:type invalid-request-type
     :reason reason
     :http-status http-status
     :wire-type wire-type
     :detail detail})))

;; ---- request decoding ---------------------------------------------------------

(def ^:private json-media-type "application/json")

(def default-max-body-bytes
  "Default retained JSON command-body limit."
  65536)

(defn- json-content-type?
  "True when the Content-Type value carries the application/json media type:
   case-insensitive, optional ; parameters accepted and ignored. The body is
   always decoded as UTF-8 per RFC 8259, whatever charset parameter appears."
  [content-type]
  (and (string? content-type)
       (= json-media-type
          (str/lower-case
           (str/trim (first (str/split content-type #";")))))))

(defn- json-whitespace?
  "The complete RFC 8259 whitespace set. clojure.string/trim is deliberately
   broader and would accept Unicode separators that are not JSON syntax."
  [c]
  (or (= c \space)
      (= c \tab)
      (= c \return)
      (= c \newline)))

(defn- trim-json-whitespace
  "Removes only RFC 8259 whitespace from both ends of the text."
  [text]
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

(defn- drain-body!
  "Consumes every remaining chunk from a jolt-http RequestBody. Rejected
   streaming requests must release the parser's bounded-channel producer."
  [request]
  (when-let [body (:body request)]
    (loop []
      (when (some? (http-body/body-recv body))
        (recur)))))

(defn- concat-body-chunks
  [chunks total]
  (let [output (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (let [length (alength chunk)]
          (dotimes [index length]
            (aset output (+ offset index) (aget chunk index)))
          (recur (rest remaining) (+ offset length)))
        output))))

(defn- bounded-body-bytes
  "Reads through body-recv with retained memory bounded linearly by
   max-body-bytes. Final concatenation briefly holds both chunks and output.
   Once the limit is crossed, discards the remainder before returning 413;
   this releases jolt-http's bounded parser producer for every finite body."
  [request max-body-bytes]
  (loop [chunks [] total 0]
    (if-let [^bytes chunk (http-body/body-recv (:body request))]
      (let [next-total (+ total (alength chunk))]
        (if (> next-total max-body-bytes)
          (do
            (drain-body! request)
            (fail-request! 413 "content-too-large" :body-too-large
                           {:max-body-bytes max-body-bytes
                            :body-consumed? true}))
          (recur (conj chunks chunk) next-total)))
      (concat-body-chunks chunks total))))

(defn- hex-value
  [c]
  (let [code (int c)]
    (cond
      (<= (int \0) code (int \9)) (- code (int \0))
      (<= (int \A) code (int \F)) (+ 10 (- code (int \A)))
      (<= (int \a) code (int \f)) (+ 10 (- code (int \a)))
      :else nil)))

(defn- percent-run
  "Reads one consecutive %HH run beginning at start. Returns its byte
   values and first following index, or nil for an incomplete/illegal escape."
  [path start]
  (let [length (count path)]
    (loop [index start bytes []]
      (if (and (< index length) (= \% (nth path index)))
        (if (< (+ index 2) length)
          (let [high (hex-value (nth path (inc index)))
                low (hex-value (nth path (+ index 2)))]
            (when (and (some? high) (some? low))
              (recur (+ index 3) (conj bytes (+ (* 16 high) low)))))
          nil)
        {:next index :bytes bytes}))))

(defn- octet-between?
  [value lower upper]
  (and (integer? value) (<= lower value upper)))

(defn- continuation-octet?
  [value]
  (octet-between? value 128 191))

(defn- valid-utf8-octets?
  "Strict RFC 3629 scalar UTF-8 validation: rejects truncation, stray
   continuation bytes, overlong forms, surrogate encodings, and values above
   U+10FFFF. Host String decoding is intentionally not used because the
   current URL decoder replaces malformed input with U+FFFD."
  [octets]
  (let [length (count octets)]
    (loop [index 0]
      (if (>= index length)
        true
        (let [octet-at (fn [offset]
                         (let [position (+ index offset)]
                           (when (< position length)
                             (bit-and (long (nth octets position)) 255))))
              b0 (octet-at 0)
              b1 (octet-at 1)
              b2 (octet-at 2)
              b3 (octet-at 3)]
          (cond
            (octet-between? b0 0 127)
            (recur (inc index))

            (and (octet-between? b0 194 223)
                 (continuation-octet? b1))
            (recur (+ index 2))

            (and (= b0 224)
                 (octet-between? b1 160 191)
                 (continuation-octet? b2))
            (recur (+ index 3))

            (and (octet-between? b0 225 236)
                 (continuation-octet? b1)
                 (continuation-octet? b2))
            (recur (+ index 3))

            (and (= b0 237)
                 (octet-between? b1 128 159)
                 (continuation-octet? b2))
            (recur (+ index 3))

            (and (octet-between? b0 238 239)
                 (continuation-octet? b1)
                 (continuation-octet? b2))
            (recur (+ index 3))

            (and (= b0 240)
                 (octet-between? b1 144 191)
                 (continuation-octet? b2)
                 (continuation-octet? b3))
            (recur (+ index 4))

            (and (octet-between? b0 241 243)
                 (continuation-octet? b1)
                 (continuation-octet? b2)
                 (continuation-octet? b3))
            (recur (+ index 4))

            (and (= b0 244)
                 (octet-between? b1 128 143)
                 (continuation-octet? b2)
                 (continuation-octet? b3))
            (recur (+ index 4))

            :else false))))))

(defn- valid-path-encoding?
  "Checks percent syntax and strictly decodes each consecutive percent-byte
   run as UTF-8 before Reitit sees it. The host URLDecoder currently replaces
   malformed UTF-8, so relying on route matching alone would silently repair
   an invalid identifier. Literal Unicode and a legitimately encoded U+FFFD
   remain valid."
  [path]
  (and
   (string? path)
   (loop [index 0]
     (if (>= index (count path))
       true
       (if (= \% (nth path index))
         (when-let [{:keys [next bytes]} (percent-run path index)]
           (if (valid-utf8-octets? bytes)
             (recur next)
             false))
         (recur (inc index)))))))

(defn- read-body-json
  "Reads the whole request body as UTF-8 and parses exactly one JSON value.
   Surrounding JSON whitespace is allowed (data.json rejects trailing
   non-whitespace via on-extra-throw); empty and unparseable bodies are
   rejected fail closed."
  [request max-body-bytes]
  (let [^bytes body-bytes (bounded-body-bytes request max-body-bytes)]
    (when-not (valid-utf8-octets? body-bytes)
      (fail-request! 400 "bad-request" :invalid-json
                     {:reason :malformed-utf8
                      :body-consumed? true}))
    ;; Body acquisition and String construction stay outside the parse catch:
    ;; transport, allocation, and runtime failures remain server-owned.
    (let [text (String. body-bytes "UTF-8")]
      (try
        (json/read-str (trim-json-whitespace text)
                       :extra-data-fn json/on-extra-throw)
        (catch :default e
          (fail-request! 400 "bad-request" :invalid-json
                         {:message (ex-message e)
                          :body-consumed? true}))))))

(def ^:private body-keys #{"payload"})

(defn- octet?
  "A JSON payload element is canonical only as an integer 0 through 255.
   Decimals and exponent forms parse as non-integers and are rejected."
  [x]
  (and (integer? x) (<= 0 x) (<= x 255)))

(defn- request->command
  "Decodes one matched request into the closed canonical command, running
   every request check -- media type, JSON syntax, closed body shape, octet
   range -- before any database statement. Path parameters are already
   non-empty percent-decoded strings by reitit's matching contract."
  [request path-params max-body-bytes]
  (when-not (json-content-type? (get (:headers request) "content-type"))
    (fail-request! 415 "unsupported-media-type" :json-required
                   {:content-type (get (:headers request) "content-type")}))
  (let [value (read-body-json request max-body-bytes)]
    (when-not (and (map? value) (= body-keys (set (keys value))))
      (fail-request! 400 "bad-request" :invalid-command-body
                     {:value value :body-consumed? true}))
    (let [payload (get value "payload")]
      (when-not (and (vector? payload) (every? octet? payload))
        (fail-request! 400 "bad-request" :invalid-command-body
                       {:value value :body-consumed? true}))
      {:request-id (:request-id path-params)
       :entity-id (:entity-id path-params)
       :payload payload})))

;; ---- wire responses -----------------------------------------------------------

(defn- json-response
  "Builds the jolt-http response map for a JSON wire value. The body is a
   String; jolt-http encodes it UTF-8 by default."
  ([status wire]
   (json-response status wire {}))
  ([status wire extra-headers]
   {:status status
    :headers (into {"Content-Type" "application/json"} extra-headers)
    :body (json/write-str wire)}))

(defn- result-wire
  "The exact committed-result envelope; key order is fixed so an exact
   replay's body is byte-identical to the original 201 body."
  [result]
  {"status" (name (:status result))
   "request-id" (:request-id result)
   "entity-id" (:entity-id result)
   "version" (:version result)
   "outbox-id" (:outbox-id result)})

(defn- error-wire
  "The exact closed error envelope; `extra` adds error-specific keys (only
   the 409 conflict carries one, its request-id)."
  ([type reason]
   {"error" {"type" type "reason" reason}})
  ([type reason extra]
   {"error" (into {"type" type "reason" reason} extra)}))

;; ---- the one transition ---------------------------------------------------------

(defn- post-command
  "Runs the one facade transition for a matched POST: decode the closed
   command, apply it through the existing durable SQLite transaction path,
   and translate the exact step outcome into the wire response. The adapter
   contract is exact: a fresh commit emits precisely one pending row; an
   exact replay emits nothing and returns the recorded result unchanged.
   Deliberate rejections and the core's conflict and unstorable-id failures
   are translated; every other failure propagates to the server's error
   handler."
  [conn request path-params max-body-bytes]
  (try
    (let [command (request->command request path-params max-body-bytes)
          step (store/apply-command! conn command)
          result (:result step)]
      (if (seq (:emitted step))
        (json-response 201 (result-wire result))
        (json-response 200 (result-wire result))))
    (catch :default e
      (let [data (ex-data e)
            type (:type data)]
        (cond
          (= invalid-request-type type)
          (do
            ;; Media-type rejection happens before parsing. Drain here for it;
            ;; syntax/shape rejections have already consumed the body, so this
            ;; is a harmless end-of-stream read for those paths.
            (when-not (get-in data [:detail :body-consumed?])
              (drain-body! request))
            (json-response (:http-status data)
                           (error-wire (:wire-type data)
                                       (name (:reason data)))))

          (= :jolt.example.outbox/request-id-conflict type)
          (json-response 409
                         (error-wire "request-id-conflict"
                                     "request-id-conflict"
                                     {"request-id" (:request-id data)}))

          (= :jolt.example.outbox.sqlite/unstorable-id type)
          (json-response 400
                         (error-wire "bad-request" "unstorable-id"))

          :else
          (throw e))))))

;; ---- public API -----------------------------------------------------------------

(defn command-handler
  "Returns the ordinary jolt-http synchronous handler for the JSON command
   facade over the open, schema-initialized SQLite connection `conn`
   (jolt.example.outbox.sqlite/init-schema! must already have run on it).
   The handler runs POST /v1/entities/:entity-id/commands/:request-id into
   jolt.example.outbox.sqlite/apply-command! and answers with the exact
   semantics documented on this namespace. It performs no I/O of its own
   beyond the request body read and the adapter's transaction path, and it
   adds no concurrency claim: requests per connection must be serialized by
   the caller, exactly as the adapter requires."
  ([conn]
   (command-handler conn {}))
  ([conn {:keys [max-body-bytes]
          :or {max-body-bytes default-max-body-bytes}}]
   (when-not (and (integer? max-body-bytes) (pos? max-body-bytes))
     (throw
      (ex-info "jolt.example.outbox.http-json: max-body-bytes must be positive"
               {:type :jolt.example.outbox.http-json/invalid-config
                :max-body-bytes max-body-bytes})))
   (fn json-command-handler [request]
     (let [path (:uri request)
           route-result
           (if (valid-path-encoding? path)
             {:match (r/match-by-path command-router path)}
             {:path-error true})]
       (cond
         (:path-error route-result)
         (do
           (drain-body! request)
           (json-response 400
                          (error-wire "bad-request"
                                      "invalid-path-encoding")))

         (nil? (:match route-result))
         (do
           (drain-body! request)
           (json-response 404
                          (error-wire "not-found" "no-matching-route")))

         (not= :post (:request-method request))
         (do
           (drain-body! request)
           (json-response 405
                          (error-wire "method-not-allowed" "post-required")
                          {"Allow" "POST"}))

         :else
         (post-command conn request
                       (:path-params (:match route-result))
                       max-body-bytes))))))
