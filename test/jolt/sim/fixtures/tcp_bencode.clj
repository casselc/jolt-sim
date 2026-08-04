(ns jolt.sim.fixtures.tcp-bencode
  "Ordinary Jolt length-framed request/reply TCP application used unchanged
  against real sockets and the hermetic POSIX loopback handler pack.

  This namespace deliberately knows nothing about jolt-sim. It depends only on
  public teensyp.server, teensyp.client, teensyp.buffer, jolt.bytes, and
  jolt.bencode. Its caller decides whether the underlying POSIX foreign calls
  reach the host or a simulated FFI world.

  Frame format: a 4-byte big-endian unsigned body length, followed by exactly
  one canonical bencode value. The body must be no longer than
  `max-frame-body-bytes`, and the bencode decoder's cursor must consume every
  declared body byte -- a value that decodes short or fails to decode at all
  is malformed framing, not a partial frame. Only the actual nREPL bencode
  profile is used: UTF-8 strings, signed integers, and maps; no booleans and
  no arbitrary binary byte-string support are claimed.

  The server handler reassembles frames split across reads (a prefix or body
  arriving in separate TCP segments) and drains every complete frame already
  buffered before a read returns, so several pipelined requests delivered in
  one recv are all served from that single read."
  (:require [jolt.bencode :as bencode]
            [jolt.bytes :as bytes]
            [jolt.host :as host]
            [teensyp.buffer :as buf]
            [teensyp.client :as client]
            [teensyp.server :as tcp]))

(def ^:private max-frame-body-bytes 512)

(def ^:private default-request-texts
  ["" "Hello, World! Ω≈ç√∫ 你好 🎉"])

(defn- be32-bytes ^bytes [^long n]
  (byte-array
   [(unchecked-byte (bit-shift-right n 24))
    (unchecked-byte (bit-shift-right n 16))
    (unchecked-byte (bit-shift-right n 8))
    (unchecked-byte n)]))

(defn- get-be32 ^long [b index]
  (let [b0 (bit-and (long (buf/get-byte b index)) 0xff)
        b1 (bit-and (long (buf/get-byte b (+ index 1))) 0xff)
        b2 (bit-and (long (buf/get-byte b (+ index 2))) 0xff)
        b3 (bit-and (long (buf/get-byte b (+ index 3))) 0xff)]
    (bit-or (bit-shift-left b0 24)
            (bit-shift-left b1 16)
            (bit-shift-left b2 8)
            b3)))

(defn- frame-bytes ^bytes [^bytes payload]
  (let [prefix (be32-bytes (alength payload))
        out (byte-array (+ 4 (alength payload)))]
    (System/arraycopy prefix 0 out 0 4)
    (System/arraycopy payload 0 out 4 (alength payload))
    out))

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (do (System/arraycopy chunk 0 out offset (alength chunk))
            (recur (rest remaining) (+ offset (alength chunk))))
        out))))

(defn- build-request [seq text]
  {:type "echo" :seq seq :text text})

(defn- build-reply [request]
  {:type "echo_ok" :seq (get request "seq") :text (get request "text")})

(defn- build-error-reply [reason]
  {:type "error" :reason (str reason)})

(defn- decode-frame-body
  "Decodes exactly one canonical bencode value from `body`. The bencode
  cursor must land exactly on the end of `body`; a value that decodes short
  (trailing bytes) or that reports :need-more or :invalid over a complete,
  correctly-length-prefixed body is malformed."
  [^bytes body]
  (let [window (bytes/window body)
        cursor (bytes/cursor window)
        decoded (bencode/decode cursor)]
    (cond
      (= :need-more (:status decoded))
      {:status :invalid :reason :incomplete-value}

      (= :invalid (:status decoded))
      {:status :invalid :reason (:reason decoded)}

      (not= (count window) (bytes/cursor-position (:cursor decoded)))
      {:status :invalid :reason :trailing-bytes}

      :else
      {:status :ok :value (:value decoded)})))

(defn- try-read-frame
  "Attempts to read one length-framed bencode value from the front of Buffer
  `b`. Returns {:status :need-more}, {:status :invalid :reason r}, or
  {:status :ok :value v}. Never advances `b`'s position for an incomplete
  prefix or body, so the unconsumed bytes wait for the next read; a declared
  length over `max-frame-body-bytes` is rejected without waiting for the rest
  of an oversized body to arrive."
  [b]
  (let [start (buf/position b)
        available (buf/remaining b)]
    (if (< available 4)
      {:status :need-more}
      (let [declared-length (get-be32 b start)]
        (cond
          (> declared-length max-frame-body-bytes)
          {:status :invalid :reason :frame-too-long}

          (< (- available 4) declared-length)
          {:status :need-more}

          :else
          (do
            (buf/set-position! b (+ start 4))
            (decode-frame-body (buf/get-bytes! b declared-length))))))))

(defn- stable-error-summary
  "Projects a server error into a stable, host-exception-free trace domain."
  [error]
  {:class (str (class error))
   :message (or (ex-message error)
                (try (host/condition-message error)
                     (catch :default _ nil)))})

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

(defn- throw-with-cleanup!
  [primary cleanup-errors]
  (if primary
    (if (seq cleanup-errors)
      (throw
       (ex-info
        (or (ex-message primary) (str primary))
        (assoc (or (ex-data primary) {})
               :tcp-bencode/primary-error (stable-error-summary primary)
               :tcp-bencode/cleanup-errors
               (cleanup-summaries cleanup-errors))
        primary))
      (throw primary))
    (when (seq cleanup-errors)
      (let [first-error (:error (first cleanup-errors))]
        (throw
         (ex-info
          "tcp-bencode fixture cleanup failed"
          {:type :jolt.sim.fixtures.tcp-bencode/cleanup-failure
           :tcp-bencode/cleanup-errors (cleanup-summaries cleanup-errors)}
          first-error))))))

(defn framed-handler
  "Builds the ordinary teensyp length-framed bencode handler used by this
  fixture. `request->reply` receives each exactly decoded request value and
  returns the value to encode in its reply frame. Accept/read/close behavior,
  fragmented-frame buffering, pipelined draining, and malformed-frame handling
  remain the same as the original echo handler."
  [request->reply]
  (fn
    ([_socket]
     {:closed? false})
    ([state socket b]
     (loop [state state]
       (if (:closed? state)
         state
         (let [frame (try-read-frame b)]
           (case (:status frame)
             :need-more
             state

             :invalid
             (let [payload (bencode/encode (build-error-reply (:reason frame)))]
               (tcp/write socket (buf/wrap (frame-bytes payload)))
               (tcp/close socket)
               (assoc state :closed? true))

             :ok
             (let [payload (bencode/encode (request->reply (:value frame)))]
               (tcp/write socket (buf/wrap (frame-bytes payload)))
               (recur state)))))))
    ([_state _ex] nil)))

(def ^:private echo-handler
  (framed-handler build-reply))

(defn- io-options [operation-context]
  (if-let [deadline-nanos (:deadline-nanos operation-context)]
    {:deadline-nanos deadline-nanos}
    {:timeout-ms 5000}))

(defn- read-exact-into!
  [connection ^bytes dest len operation-context]
  (loop [offset 0]
    (when (< offset len)
      (let [n (client/receive-into! connection dest offset (- len offset)
                                     (io-options operation-context))]
        (when (nil? n)
          (throw (ex-info "tcp-bencode fixture: connection EOF mid-frame"
                          {:offset offset :length len})))
        (recur (+ offset n))))))

(defn- receive-frame!
  "Blocks for one length-framed bencode reply and returns
  {:value decoded-value :bytes total-frame-length}."
  [connection operation-context]
  (let [prefix (byte-array 4)]
    (read-exact-into! connection prefix 4 operation-context)
    (let [declared-length (get-be32 (buf/wrap prefix) 0)
          _ (when (> declared-length max-frame-body-bytes)
              (throw
               (ex-info "tcp-bencode fixture: reply frame is too long"
                        {:declared-length declared-length
                         :max-frame-body-bytes max-frame-body-bytes})))
          body (byte-array declared-length)]
      (read-exact-into! connection body declared-length operation-context)
      (let [decoded (decode-frame-body body)]
        (when-not (= :ok (:status decoded))
          (throw (ex-info "tcp-bencode fixture: reply frame failed to decode"
                          {:reason (:reason decoded)})))
        {:value (:value decoded) :bytes (+ 4 declared-length)}))))

(defn exchange!
  "Sends `requests` as one pipelined length-framed bencode write over an already
  connected public teensyp client, then reads one ordered reply per request.
  The caller retains connection lifecycle ownership. Returns only canonical
  request/reply values and byte counts; framing and decoding use the same
  private implementation as exercise-tcp-bencode. The optional operation
  context carries one unchanged absolute :deadline-nanos through the write and
  every prefix/body read; the original arity retains its fixed per-call
  timeout behavior."
  ([connection requests]
   (exchange! connection requests nil))
  ([connection requests operation-context]
   (let [requests (vec requests)
         combined
         (concat-byte-arrays
          (mapv #(frame-bytes (bencode/encode %)) requests))
         _ (client/send-all! connection combined
                             (io-options operation-context))
         replies
         (mapv (fn [_request]
                 (receive-frame! connection operation-context))
               requests)]
     {:sent-bytes (alength combined)
      :received-bytes (reduce + (map :bytes replies))
      :requests requests
      :replies (mapv :value replies)})))

(defn exercise-tcp-bencode
  "Exercises the length-framed bencode request/reply protocol through only the
  public teensyp.server/teensyp.client/teensyp.buffer and jolt.bencode APIs.

  `texts` (default two entries spanning an empty, an ASCII, and a Unicode text
  boundary in one mixed field) becomes one pipelined write: each text builds a
  seq-numbered echo request, every request's frame is concatenated into a
  single client send so the server must drain multiple complete frames from
  one buffered read, and each correlated echo_ok reply is read back in order.
  Port zero selects an ephemeral listener; the bound port is read from the
  server's own return value."
  ([] (exercise-tcp-bencode default-request-texts))
  ([texts]
   (let [server-errors (atom [])
         server
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler echo-handler
          :error-logger
          (fn [error]
            (swap! server-errors conj (stable-error-summary error))))
         connection* (atom nil)
         body
         (try
           {:value
            (let [port (:port server)
                  connection
                  (client/connect "127.0.0.1" port
                                  {:connect-timeout-ms 5000})
                  _ (reset! connection* connection)
                  requests
                  (into []
                        (map-indexed
                         (fn [i text] (build-request (inc i) text)))
                        texts)
                  exchange (exchange! connection requests)
                  first-close
                  (client/close! connection)
                  second-close
                  (client/close! connection)]
              (assoc exchange
                     :port port
                     :close-results
                     {:connection [first-close second-close]}))}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep
           identity
           [(when-let [connection @connection*]
              (cleanup-attempt
               :connection-close
               #(client/close! connection)))
            (cleanup-attempt
             :server-stop
             #(tcp/stop-server server))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     ;; stop-server quiesces the reactor and active handlers, either of which
     ;; may report a late error. Snapshot only after shutdown has completed.
     (assoc (:value body) :server-errors @server-errors))))
