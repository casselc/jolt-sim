(ns jolt.sim.viewer.remote-session
  "Bounded attachment to another loopback Ripple Session producer.

  Read-only attachment remains the default. Explicit command-capable callers
  may send one exact revision-scoped step once and reconcile an ambiguous
  result by reading the producer journal. Every operation opens one fresh
  teensyp connection and consumes one strict Content-Length-framed HTTP
  response under a single absolute monotonic deadline. The configured producer
  epoch is never learned or changed from a response: a missing or different
  epoch is a typed source restart, and the caller must explicitly construct a
  new attachment before using that producer."
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [teensyp.client :as client]))

(def source-restarted ::source-restarted)
(def invalid-source ::invalid-source)
(def invalid-response ::invalid-response)
(def source-unavailable ::source-unavailable)
(def step-outcome-unknown ::step-outcome-unknown)
(def reconciliation-limit-exceeded ::reconciliation-limit-exceeded)

(def ^:private source-keys
  #{:port :capability-token :session-instance-id :timeout-ms})
(def ^:private default-timeout-ms 5000)
(def ^:private maximum-frame-bytes (* 16 1024 1024))
(def ^:private maximum-header-bytes 16384)
(def ^:private maximum-step-response-bytes 16384)
(def ^:private maximum-reconciliation-pages 64)
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

(defn- decode-utf8-exact [^bytes bytes offset length]
  ;; Jolt's host String constructor replaces malformed UTF-8 with U+FFFD.
  ;; Round-tripping is an exact validator because RFC 3629 scalar UTF-8 has
  ;; one canonical byte representation for every accepted string.
  (let [text (String. bytes offset length "UTF-8")
        encoded (.getBytes ^String text "UTF-8")]
    (when-not (and (= length (alength ^bytes encoded))
                   (loop [index 0]
                     (cond
                       (= index length) true
                       (= (aget bytes (+ offset index))
                          (aget ^bytes encoded index))
                       (recur (inc index))
                       :else false)))
      (invalid-response! :invalid-utf8 nil))
    text))

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
                     (decode-utf8-exact bytes body-start content-length))]
          (when-not (and (map? frame)
                         (map? (:journal frame))
                         (= cursor (get-in frame [:journal :cursor]))
                         (= next-cursor
                            (get-in frame [:journal :next-cursor])))
            (invalid-response! :next-cursor-mismatch nil))
          frame)))))

(defn- checked-body-text
  "Checks command-response framing and media type. Epoch and status-specific
  interpretation happen only after the exact body is available, because an
  authority rejection intentionally carries no producer epoch."
  [source ^bytes bytes length boundary head maximum-body-bytes]
  (let [body-start (+ boundary 4)
        content-length (:content-length head)
        expected-total (+ body-start content-length)
        observed-instance (get-in head [:headers
                                        "x-jolt-sim-session-instance"])]
    (when (> content-length maximum-body-bytes)
      (invalid-response! :body-too-large
                         {:limit maximum-body-bytes
                          :actual content-length}))
    (cond
      (< length expected-total)
      (invalid-response! :truncated-body
                         {:expected content-length
                          :actual (- length body-start)})

      (> length expected-total)
      (invalid-response! :surplus-body nil))
    (let [content-type (some-> (get-in head [:headers "content-type"])
                               string/lower-case)]
      (when-not (contains? #{"application/edn"
                             "application/edn; charset=utf-8"}
                           content-type)
        (invalid-response! :invalid-content-type nil)))
    {:status (:status head)
     :session-instance-id observed-instance
     :body (parse-edn-exact
            (decode-utf8-exact bytes body-start content-length))}))

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

(defn- step-unknown [phase error]
  (let [data (ex-data error)]
    (ex-info "Remote Ripple Session step outcome is unknown"
             (cond-> {:type step-outcome-unknown
                      :phase phase}
               (keyword? (:type data)) (assoc :cause-type (:type data))
               (integer? (:status data)) (assoc :status (:status data)))
             error)))

(defn- valid-branch? [branch]
  (let [action (:action branch)
        revision (:revision branch)
        kind (first action)
        value (second action)]
    (and (map? branch)
         (= #{:revision :action} (set (keys branch)))
         (integer? revision)
         (<= 0 revision)
         (< revision Long/MAX_VALUE)
         (vector? action)
         (= 2 (count action))
         (contains? #{:run :advance} kind)
         (integer? value)
         (<= Long/MIN_VALUE value Long/MAX_VALUE)
         (or (= :advance kind) (<= 0 value)))))

(defn- validate-step-coordinate! [branch cursor]
  (when-not (valid-branch? branch)
    (throw (source-error :invalid-branch nil)))
  (when-not (and (integer? cursor)
                 (<= 0 cursor Long/MAX_VALUE))
    (throw (source-error :invalid-cursor cursor)))
  [branch cursor])

(defn- step-request-bytes [source branch cursor]
  (let [[kind value] (:action branch)
        body
        (str "{\"version\":1,\"cursor\":\"" cursor
             "\",\"branch\":{\"revision\":\"" (:revision branch)
             "\",\"kind\":\"" (name kind)
             "\",\"value\":\"" value "\"}}")]
    (.getBytes
     (str "POST /api/session-step HTTP/1.1\r\n"
          "Host: 127.0.0.1:" (:port source) "\r\n"
          "Accept: application/edn\r\n"
          "Content-Type: application/json\r\n"
          "Content-Length: " (alength (.getBytes ^String body "UTF-8")) "\r\n"
          "Connection: close\r\n"
          "X-Jolt-Sim-Capability: " (:capability-token source) "\r\n"
          "X-Jolt-Sim-Session-Instance: "
          (:session-instance-id source) "\r\n\r\n"
          body)
     "UTF-8")))

(defn- valid-frame-error? [value phase]
  (and (map? value)
       (= (cond-> #{:type :phase}
            (contains? value :attempts) (conj :attempts :max-attempts))
          (set (keys value)))
       (keyword? (:type value))
       (= phase (:phase value))
       (or (not (contains? value :attempts))
           (and (integer? (:attempts value))
                (pos? (:attempts value))
                (integer? (:max-attempts value))
                (pos? (:max-attempts value))
                (<= (:attempts value) (:max-attempts value))))))

(defn- valid-frame-status? [receipt phase]
  (case (:frame-status receipt)
    :available (not (contains? receipt :frame-error))
    :unavailable (valid-frame-error? (:frame-error receipt) phase)
    false))

(defn- checked-step-receipt [branch status body]
  (let [base-keys #{:version :status :committed? :frame-status}
        frame-keys (if (= :unavailable (:frame-status body))
                     #{:frame-error}
                     #{})]
    (cond
      (and (= 200 status)
           (= :committed (:status body))
           (= (into (conj base-keys :ack) frame-keys) (set (keys body)))
           (= 1 (:version body))
           (true? (:committed? body))
           (= {:branch branch :revision (inc (:revision branch))}
              (:ack body))
           (valid-frame-status? body :post-commit))
      body

      (and (= 409 status)
           (= :stale (:status body))
           (= (into (conj base-keys :stale) frame-keys) (set (keys body)))
           (= 1 (:version body))
           (false? (:committed? body))
           (= {:expected-revision (:revision branch)
               :actual-revision (get-in body [:stale :actual-revision])
               :branch branch}
              (:stale body))
           (integer? (get-in body [:stale :actual-revision]))
           (<= 0 (get-in body [:stale :actual-revision]) Long/MAX_VALUE)
           (not= (:revision branch)
                 (get-in body [:stale :actual-revision]))
           (valid-frame-status? body :stale-refresh))
      body

      (and (= 409 status)
           (map? body)
           (= :session-instance-mismatch (:error body)))
      (throw (ex-info "Remote Ripple Session producer restarted"
                      {:type source-restarted
                       :expected-session-instance-id nil
                       :observed-session-instance-id nil}))

      :else
      (invalid-response! :invalid-step-receipt {:status status}))))

(def ^:private definite-step-errors
  #{[400 :invalid-session-step]
    [400 :invalid-session-cursor]
    [403 :forbidden]
    [404 :session-step-unavailable]
    [409 :session-instance-mismatch]
    [409 :session-step-rejected]
    [413 :request-too-large]
    [415 :expected-application-json]
    [429 :session-step-busy]
    [429 :viewer-busy]})

(defn- definite-step-error [status body]
  (when (and (map? body)
             (contains? #{#{:error} #{:error :detail}} (set (keys body)))
             (contains? definite-step-errors [status (:error body)]))
    {:status status :reason (:error body)}))

(defn- source-unavailable-error [status reason]
  (ex-info "Remote Ripple Session step was definitely not committed"
           {:type source-unavailable :status status :reason reason}))

(defn- checked-step-outcome [source branch response]
  (let [{:keys [status body session-instance-id]} response
        definite (definite-step-error status body)]
    (cond
      ;; Authority is checked before the producer epoch and an unauthorized
      ;; response deliberately has no epoch. Its closed 403 is nevertheless a
      ;; definitive pre-body, pre-mutation rejection.
      (and (= {:status 403 :reason :forbidden} definite)
           (nil? session-instance-id))
      (throw (source-unavailable-error 403 :forbidden))

      (nil? session-instance-id)
      (invalid-response! :missing-session-instance nil)

      (not= (:session-instance-id source) session-instance-id)
      (throw (ex-info "Remote Ripple Session producer restarted"
                      {:type source-restarted
                       :expected-session-instance-id
                       (:session-instance-id source)
                       :observed-session-instance-id session-instance-id}))

      (<= 500 status 599)
      (throw (ex-info "remote step returned a server error"
                      {:type ::server-error :status status}))

      (or (= 200 status)
          (and (= 409 status) (= :stale (:status body))))
      (checked-step-receipt branch status body)

      definite
      (throw (source-unavailable-error (:status definite) (:reason definite)))

      :else
      (invalid-response! :invalid-step-response {:status status}))))

(defn- receive-step-response! [ops source connection deadline]
  (let [capacity (+ maximum-header-bytes maximum-step-response-bytes 1)
        response (byte-array capacity)]
    (loop [length 0 boundary nil head nil expected-total nil]
      (let [read-limit (if boundary
                         (- (inc expected-total) length)
                         (- maximum-header-bytes length))]
        (when (<= read-limit 0)
          (if boundary
            (invalid-response! :surplus-body nil)
            (invalid-response! :headers-too-large
                               {:limit maximum-header-bytes})))
        (let [received ((:receive ops) connection response length read-limit
                        deadline)]
          (if (nil? received)
            (if boundary
              (checked-body-text source response length boundary head
                                 maximum-step-response-bytes)
              (invalid-response! :truncated-headers nil))
            (let [next-length (+ length received)
                  next-boundary (or boundary
                                    (find-boundary response next-length
                                                   (max 0 (- length 3))))
                  next-head (if (and next-boundary (nil? head))
                              (parse-head response next-boundary)
                              head)
                  next-expected (if (and next-boundary next-head)
                                  (+ next-boundary 4
                                     (:content-length next-head))
                                  expected-total)]
              (when (and next-head
                         (> (:content-length next-head)
                            maximum-step-response-bytes))
                (invalid-response! :body-too-large
                                   {:limit maximum-step-response-bytes
                                    :actual (:content-length next-head)}))
              (when (and next-expected (> next-length next-expected))
                (invalid-response! :surplus-body nil))
              (recur next-length next-boundary next-head next-expected))))))))

(defn- receipt->step-result [receipt]
  (cond-> {:jolt.sim.session-view/type :step-result
           :status (:status receipt)
           :committed? (:committed? receipt)
           :frame (when (= :available (:frame-status receipt))
                    {:jolt.sim.session-view/type :frame})
           :frame-error (:frame-error receipt)}
    (= :committed (:status receipt)) (assoc :ack (:ack receipt))
    (= :stale (:status receipt)) (assoc :ack nil :stale
                                        (assoc (:stale receipt)
                                               :type
                                               :jolt.sim.session/stale-branch))))

(defn- step-frame* [ops source branch cursor]
  (validate-step-coordinate! branch cursor)
  (let [deadline (+ ((:now ops)) (* (:timeout-ms source) nanos-per-ms))
        connection-result
        (try
          {:connection ((:connect ops) (:port source) deadline)}
          (catch :default error {:error error}))]
    (when-let [error (:error connection-result)]
      (throw (step-unknown :connect error)))
    (let [connection (:connection connection-result)
          outcome
          (try
            ((:send ops) connection (step-request-bytes source branch cursor)
             deadline)
            {:receipt
             (checked-step-outcome
              source branch
              (receive-step-response! ops source connection deadline))}
            (catch :default error
              (if (contains? #{source-restarted source-unavailable}
                             (:type (ex-data error)))
                {:error error}
                {:error (step-unknown :exchange error)})))
          cleanup-error
          (try
            ((:close ops) connection)
            nil
            (catch :default error error))]
      (cond
        ;; Once a complete, pinned, closed receipt is recognized, socket close
        ;; cannot make the already-known command outcome ambiguous.
        (:receipt outcome)
        (receipt->step-result (:receipt outcome))

        (:error outcome)
        (throw (if cleanup-error
                 (preserve-primary-with-cleanup (:error outcome) cleanup-error)
                 (:error outcome)))

        cleanup-error
        (throw (step-unknown :close cleanup-error))))))

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

(defn stepper
  "Returns a trusted `(branch cursor -> step-result)` closure for Ripple.

  Each invocation sends one exact revision-scoped command once. Connect,
  send, receive, 5xx, and malformed-response failures throw
  `::step-outcome-unknown`; callers must reconcile or explicitly retry rather
  than assuming the command did not commit. A complete pinned receipt remains
  authoritative even when closing its fresh connection fails."
  [source max-frame-bytes]
  (let [source (with-frame-bound source max-frame-bytes)
        ops (real-ops)]
    (fn [branch cursor]
      (step-frame* ops source branch cursor))))

(defn- valid-reconciliation-entry? [entry]
  (let [seq (:seq entry)]
    (and (map? entry)
         (integer? seq)
         (<= 0 seq Long/MAX_VALUE)
         (if (zero? seq)
           (and (= :start (:command entry))
                (not (contains? entry :branch)))
           (and (= :step (:command entry))
                (valid-branch? (:branch entry))
                (= (dec seq) (get-in entry [:branch :revision])))))))

(defn- reconciliation-entry [frame target-seq]
  (let [journal (:journal frame)
        cursor (:cursor journal)
        next-cursor (:next-cursor journal)
        entries (:entries journal)]
    (when-not (and (map? journal)
                   (integer? cursor)
                   (integer? next-cursor)
                   (<= 0 cursor next-cursor)
                   (vector? entries)
                   (= (- next-cursor cursor) (count entries))
                   (every? true?
                           (map-indexed
                            (fn [index entry]
                              (and (= (+ cursor index) (:seq entry))
                                   (valid-reconciliation-entry? entry)))
                            entries)))
      (invalid-response! :invalid-reconciliation-journal nil))
    (when (and (<= cursor target-seq) (< target-seq next-cursor))
      (nth entries (- target-seq cursor)))))

(defn- reconcile-step* [read-frame branch cursor]
  (validate-step-coordinate! branch cursor)
  (let [target-seq (inc (:revision branch))]
    (when (> cursor target-seq)
      (throw (source-error :cursor-after-command
                           {:cursor cursor :command-seq target-seq})))
    (loop [page-cursor cursor
           page-number 0
           observed-count nil
           observed-revision nil]
      (when (>= page-number maximum-reconciliation-pages)
        (throw
         (ex-info "Remote Session reconciliation page limit was reached"
                  {:type reconciliation-limit-exceeded
                   :maximum-pages maximum-reconciliation-pages
                   :cursor page-cursor})))
      (let [frame (read-frame page-cursor)
            journal (:journal frame)
            entry (reconciliation-entry frame target-seq)
            next-cursor (:next-cursor journal)
            count (:count journal)
            revision (:revision frame)]
        (when-not (and (integer? count)
                       (pos? count)
                       (integer? revision)
                       (= revision (dec count))
                       (or (nil? observed-count)
                           (<= observed-count count))
                       (or (nil? observed-revision)
                           (<= observed-revision revision))
                       (= page-cursor (:cursor journal))
                       (<= next-cursor count)
                       (or (not (:remaining? journal))
                           (< page-cursor next-cursor))
                       (= (:remaining? journal) (< next-cursor count)))
          (invalid-response! :invalid-reconciliation-journal nil))
        (cond
          entry
          (if (= branch (:branch entry))
            {:jolt.sim.viewer.remote-session/type :step-reconciliation
             :status :committed
             :branch branch
             :cursor cursor
             :observed {:seq target-seq
                        :command :step
                        :branch branch}}
            {:jolt.sim.viewer.remote-session/type :step-reconciliation
             :status :different
             :branch branch
             :cursor cursor
             :observed {:seq target-seq
                        :command :step
                        :branch (:branch entry)}})

          (< target-seq next-cursor)
          ;; The target sequence must be present in an append-only journal.
          ;; If this page skipped it, the frame is not safe reconciliation
          ;; data.
          (invalid-response! :missing-reconciliation-entry
                             {:command-seq target-seq})

          (:remaining? journal)
          (recur next-cursor (inc page-number) count revision)

          :else
          {:jolt.sim.viewer.remote-session/type :step-reconciliation
           :status :missing
           :branch branch
           :cursor cursor
           :observed-revision (:revision frame)
           :journal-count count})))))

(defn reconciler
  "Returns an explicit journal reconciler for an ambiguous remote step.

  The caller must supply the original exact branch and the original journal
  cursor. The result is closed data with status `:committed`, `:different`, or
  `:missing`. It never sends a command and never adopts a new producer epoch.
  One call reads at most 64 pages; exceeding that bound throws the typed
  `::reconciliation-limit-exceeded` rather than scanning without limit."
  [source max-frame-bytes]
  (let [source (with-frame-bound source max-frame-bytes)
        ops (real-ops)
        read-frame (fn [cursor] (read-frame* ops source cursor))]
    (fn [branch cursor]
      (reconcile-step* read-frame branch cursor))))

(defn attachment
  "Builds the closed UI-neutral remote Session capability map.

  The returned functions share one validated, pinned source coordinate but
  open a fresh loopback connection for every operation. `:read-frame` is
  inspection-only, `:step-frame!` sends one command once, and
  `:reconcile-step!` performs only frame/journal reads from the command's
  original cursor."
  [source max-frame-bytes]
  (let [source (with-frame-bound source max-frame-bytes)
        ops (real-ops)
        read-frame (fn [cursor] (read-frame* ops source cursor))]
    {:read-frame read-frame
     :step-frame! (fn [branch cursor]
                    (step-frame* ops source branch cursor))
     :reconcile-step! (fn [branch cursor]
                        (reconcile-step* read-frame branch cursor))}))
