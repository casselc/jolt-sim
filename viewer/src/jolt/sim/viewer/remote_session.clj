(ns jolt.sim.viewer.remote-session
  "Bounded read-only attachment to another loopback Ripple Session producer.

  Each frame read opens one fresh teensyp connection and consumes one strict
  Content-Length-framed HTTP response under a single absolute monotonic
  deadline. The configured producer epoch is never learned or changed from a
  response: a missing or different epoch is a typed source restart, and the
  caller must explicitly construct a new attachment before reading that
  producer."
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [teensyp.client :as client]))

(def source-restarted ::source-restarted)
(def invalid-source ::invalid-source)
(def invalid-response ::invalid-response)
(def source-unavailable ::source-unavailable)

(def ^:private source-keys
  #{:port :capability-token :session-instance-id :timeout-ms})
(def ^:private default-timeout-ms 5000)
(def ^:private maximum-frame-bytes (* 16 1024 1024))
(def ^:private maximum-header-bytes 16384)
(def ^:private nanos-per-ms 1000000)
(def ^:private minimum-token-length 32)
(def ^:private maximum-token-length 256)
(def ^:private maximum-timeout-ms 60000)
(def ^:private minimum-instance-length 16)
(def ^:private maximum-instance-length 128)
(def ^:private end-of-edn (Object.))

(defn- source-error [reason detail]
  (ex-info "Ripple rejected its remote Session source"
           {:type invalid-source :reason reason :detail detail}))

(defn- header-safe? [value]
  (and (string? value)
       (= value (string/trim value))
       (every? (fn [ch]
                 (let [code (int ch)]
                   (<= 32 code 126)))
               value)))

(defn- valid-instance-id? [value]
  (and (string? value)
       (<= minimum-instance-length (count value) maximum-instance-length)
       (boolean (re-matches #"[A-Za-z0-9._~-]+" value))))

(defn validate-source!
  "Validates one fixed loopback producer coordinate.

  The capability is authority and therefore must be safe to place in one HTTP
  header. `:session-instance-id` is the producer epoch to pin, never a value
  discovered from the server."
  [source]
  (when-not (map? source)
    (throw (source-error :not-a-map (str (class source)))))
  (let [unknown (into #{} (remove source-keys) (keys source))]
    (when (seq unknown)
      (throw (source-error :unknown-keys unknown))))
  (let [port (:port source)
        token (:capability-token source)
        instance-id (:session-instance-id source)
        timeout-ms (get source :timeout-ms default-timeout-ms)]
    (when-not (and (integer? port) (<= 1 (long port) 65535))
      (throw (source-error :invalid-port port)))
    (when-not (and (header-safe? token)
                   (<= minimum-token-length
                       (count token)
                       maximum-token-length))
      (throw (source-error :invalid-capability-token
                           {:minimum-length minimum-token-length
                            :maximum-length maximum-token-length})))
    (when-not (valid-instance-id? instance-id)
      (throw (source-error :invalid-session-instance-id
                           {:minimum-length minimum-instance-length
                            :maximum-length maximum-instance-length})))
    (when-not (and (integer? timeout-ms)
                   (<= 1 timeout-ms maximum-timeout-ms))
      (throw (source-error :invalid-timeout-ms timeout-ms)))
    {:port port
     :capability-token token
     :session-instance-id instance-id
     :timeout-ms timeout-ms}))

(defn- with-frame-bound [source max-frame-bytes]
  (when-not (and (integer? max-frame-bytes)
                 (<= 1 max-frame-bytes maximum-frame-bytes))
    (throw (source-error :invalid-max-frame-bytes
                         {:value max-frame-bytes
                          :maximum maximum-frame-bytes})))
  (assoc (validate-source! source) :max-frame-bytes max-frame-bytes))

(defn- find-boundary [^bytes bytes length start]
  (loop [index (max 0 start)]
    (cond
      (> (+ index 4) length) nil
      (and (= 13 (aget bytes index))
           (= 10 (aget bytes (inc index)))
           (= 13 (aget bytes (+ index 2)))
           (= 10 (aget bytes (+ index 3)))) index
      :else (recur (inc index)))))

(defn- invalid-response! [reason detail]
  (throw (ex-info "Remote Ripple returned an invalid HTTP response"
                  {:type invalid-response :reason reason :detail detail})))

(defn- valid-field-name? [name]
  (boolean (re-matches #"[!#$%&'*+.^_`|~0-9A-Za-z-]+" name)))

(defn- valid-field-value? [value]
  (every? (fn [ch]
            (let [code (int ch)]
              (or (= code 9) (<= 32 code 126))))
          value))

(defn- parse-head [^bytes bytes boundary]
  (let [text (String. bytes 0 boundary "ISO-8859-1")
        lines (string/split text #"\r\n" -1)
        status-line (first lines)]
    (when-not (and status-line
                   (re-matches #"HTTP/1\.[01] [0-9]{3}(?: .*)?" status-line))
      (invalid-response! :malformed-status-line nil))
    (let [status (parse-long (subs status-line 9 12))
          headers
          (reduce
           (fn [result line]
             (when (or (string/blank? line)
                       (= \space (first line))
                       (= \tab (first line)))
               (invalid-response! :malformed-header nil))
             (let [colon (string/index-of line ":")]
               (when (or (nil? colon) (zero? colon))
                 (invalid-response! :malformed-header nil))
               (let [raw-name (subs line 0 colon)
                     name (string/lower-case raw-name)
                     value (string/trim (subs line (inc colon)))]
                 (when-not (and (valid-field-name? raw-name)
                                (valid-field-value? value))
                   (invalid-response! :malformed-header nil))
                 ;; Duplicate fields are unnecessary in this closed protocol;
                 ;; rejecting all of them also rejects every ambiguous framing
                 ;; or producer-epoch representation.
                 (when (contains? result name)
                   (invalid-response! :duplicate-header {:name name}))
                 (assoc result name value))))
           {}
           (rest lines))]
      (when (contains? headers "transfer-encoding")
        (invalid-response! :transfer-encoding-forbidden nil))
      (let [raw-length (get headers "content-length")]
        (when-not (and raw-length (re-matches #"[0-9]+" raw-length))
          (invalid-response! :content-length-required nil))
        (let [length (parse-long raw-length)]
          (when (nil? length)
            (invalid-response! :invalid-content-length nil))
          {:status status :headers headers :content-length length})))))

(defn- parse-edn-exact [text]
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-edn)
          [trailing _] (read+string reader false end-of-edn)]
      (when (identical? value end-of-edn)
        (invalid-response! :empty-body nil))
      (when-not (identical? trailing end-of-edn)
        (invalid-response! :trailing-body nil))
      ;; `read+string` establishes exact framing; edn/read-string applies the
      ;; public EDN reader's tag and evaluation restrictions to the same text.
      (edn/read-string text))
    (catch :default error
      (if (= invalid-response (:type (ex-data error)))
        (throw error)
        (invalid-response! :invalid-edn nil)))))

(defn- checked-response [source cursor ^bytes bytes length boundary head]
  (let [body-start (+ boundary 4)
        content-length (:content-length head)
        expected-total (+ body-start content-length)
        observed-instance (get-in head [:headers
                                        "x-jolt-sim-session-instance"])]
    (when (> content-length (:max-frame-bytes source))
      (invalid-response! :body-too-large
                         {:limit (:max-frame-bytes source)
                          :actual content-length}))
    (cond
      (< length expected-total)
      (invalid-response! :truncated-body
                         {:expected content-length
                          :actual (- length body-start)})

      (> length expected-total)
      (invalid-response! :surplus-body nil))
    ;; A supplied epoch is always authoritative enough to detect a restart,
    ;; including on an error response. An authority rejection legitimately
    ;; carries no epoch and remains source-unavailable rather than being
    ;; misreported as a restart. A successful frame, however, must carry the
    ;; exact pinned epoch.
    (when (and observed-instance
               (not= (:session-instance-id source) observed-instance))
      (throw (ex-info "Remote Ripple Session producer restarted"
                      {:type source-restarted
                       :expected-session-instance-id
                       (:session-instance-id source)
                       :observed-session-instance-id observed-instance})))
    (when-not (= 200 (:status head))
      (throw (ex-info "Remote Ripple Session frame is unavailable"
                      {:type source-unavailable :status (:status head)})))
    (when (nil? observed-instance)
      (throw (ex-info "Remote Ripple Session producer restarted"
                      {:type source-restarted
                       :expected-session-instance-id
                       (:session-instance-id source)
                       :observed-session-instance-id nil})))
    (let [content-type (some-> (get-in head [:headers "content-type"])
                               string/lower-case)
          raw-next-cursor (get-in head
                                  [:headers
                                   "x-jolt-sim-journal-next-cursor"])]
      (when-not (contains? #{"application/edn"
                             "application/edn; charset=utf-8"}
                           content-type)
        (invalid-response! :invalid-content-type nil))
      (when-not (and raw-next-cursor
                     (re-matches #"[0-9]+" raw-next-cursor))
        (invalid-response! :invalid-next-cursor nil))
      (let [next-cursor (parse-long raw-next-cursor)]
        (when-not (and next-cursor
                       (= raw-next-cursor (str next-cursor)))
          (invalid-response! :invalid-next-cursor nil))
        (let [frame (parse-edn-exact
                     (String. bytes body-start content-length "UTF-8"))]
          (when-not (and (map? frame)
                         (map? (:journal frame))
                         (= cursor (get-in frame [:journal :cursor]))
                         (= next-cursor
                            (get-in frame [:journal :next-cursor])))
            (invalid-response! :next-cursor-mismatch nil))
          frame)))))

(defn- real-ops []
  {:now (fn [] (jolt.host/mono-nanos))
   :connect (fn [port deadline]
              (client/connect "127.0.0.1" port
                              {:deadline-nanos deadline}))
   :send (fn [connection payload deadline]
           (client/send-all! connection payload
                             {:deadline-nanos deadline}))
   :receive (fn [connection destination offset length deadline]
              (client/receive-into! connection destination offset length
                                    {:deadline-nanos deadline}))
   :close client/close!})

(defn- cleanup-error-data [error]
  {:class (str (class error))
   :type (:type (ex-data error))})

(defn- preserve-primary-with-cleanup
  [primary cleanup]
  (ex-info (ex-message primary)
           (assoc (or (ex-data primary) {})
                  :remote-session/cleanup-error
                  (cleanup-error-data cleanup))
           primary))

(defn- read-frame* [ops source cursor]
  (when-not (and (integer? cursor)
                 (<= 0 cursor Long/MAX_VALUE))
    (throw (source-error :invalid-cursor cursor)))
  (let [deadline (+ ((:now ops)) (* (:timeout-ms source) nanos-per-ms))
        request
        (.getBytes
         (str "GET /api/session-frame HTTP/1.1\r\n"
              "Host: 127.0.0.1:" (:port source) "\r\n"
              "Accept: application/edn\r\n"
              "Connection: close\r\n"
              "X-Jolt-Sim-Capability: " (:capability-token source) "\r\n"
              "X-Jolt-Sim-Journal-Cursor: " cursor "\r\n"
              "X-Jolt-Sim-Session-Instance: "
              (:session-instance-id source) "\r\n\r\n")
         "UTF-8")
        capacity (+ maximum-header-bytes (:max-frame-bytes source) 1)
        response (byte-array capacity)
        connection ((:connect ops) (:port source) deadline)
        outcome
        (try
          ((:send ops) connection request deadline)
          {:value
           (loop [length 0 boundary nil head nil expected-total nil]
             (let [read-limit
                   (if boundary
                     (- (inc expected-total) length)
                     (- maximum-header-bytes length))]
               (when (<= read-limit 0)
                 (if boundary
                   (invalid-response! :surplus-body nil)
                   (invalid-response! :headers-too-large
                                      {:limit maximum-header-bytes})))
               (let [received
                     ((:receive ops) connection response length read-limit
                      deadline)]
                 (if (nil? received)
                   (if boundary
                     (checked-response source cursor response length boundary
                                       head)
                     (invalid-response! :truncated-headers nil))
                   (let [next-length (+ length received)
                         next-boundary
                         (or boundary
                             (find-boundary response next-length
                                            (max 0 (- length 3))))
                         next-head
                         (if (and next-boundary (nil? head))
                           (parse-head response next-boundary)
                           head)
                         next-expected
                         (if (and next-boundary next-head)
                           (+ next-boundary 4 (:content-length next-head))
                           expected-total)]
                     (when (and next-head
                                (> (:content-length next-head)
                                   (:max-frame-bytes source)))
                       (invalid-response! :body-too-large
                                          {:limit (:max-frame-bytes source)
                                           :actual
                                           (:content-length next-head)}))
                     (when (and next-expected (> next-length next-expected))
                       (invalid-response! :surplus-body nil))
                     (recur next-length next-boundary next-head
                            next-expected))))))}
          (catch :default error {:error error}))
        cleanup-error
        (try
          ((:close ops) connection)
          nil
          (catch :default error error))]
    (cond
      (:error outcome)
      (throw (if cleanup-error
               (preserve-primary-with-cleanup (:error outcome) cleanup-error)
               (:error outcome)))

      cleanup-error
      (throw cleanup-error)

      :else (:value outcome))))

(defn reader
  "Returns a trusted `cursor -> coherent-frame` closure for Ripple services.

  The returned closure pins the validated source epoch for its entire
  lifetime. Construct a new reader explicitly to adopt a restarted producer."
  [source max-frame-bytes]
  (let [source (with-frame-bound source max-frame-bytes)
        ops (real-ops)]
    (fn [cursor]
      (read-frame* ops source cursor))))
