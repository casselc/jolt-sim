(ns perturb.stream
  "A blocking connection adapter over the socket effect — perturb's port of
  `teensyp.stream`.

  WHAT IS THE SAME. The original exposes a `conn` object rather than an
  InputStream/OutputStream pair, with four blocking operations on a protocol —
  `conn-recv`, `conn-read-line`, `conn-send`, `conn-close` — and an LF scan over
  a mutable `leftover` holding the unconsumed tail of the last chunk. That is
  what is here: the same protocol, the same four operations, and
  `conn-read-line` written as the same `loop` over the same three arms
  (delimiter found / read more / end of stream), with the CR strip and the
  final-unterminated-fragment rule in the same places.

  WHAT PERTURB TOOK AWAY, and what stands in for it:

  - CORE.ASYNC. In the original the chunks arrive on a channel that the server's
    read arity feeds from a worker thread, and `conn-recv` is `(a/<!! in)`.
    perturb has no threads and no channels, and `perturb.wire/recv` is itself
    the blocking read, so the channel is gone: `recv-chunk` performs the effect
    where the original took from the channel. End of stream is an empty octet
    view rather than a closed channel, so `recv-chunk` maps it back to `nil` and
    every caller reads exactly as it did before.

    This is the load-bearing deletion, and it deletes a claim with it. The
    original's docstring argues backpressure: a producer parked on a blocking
    `>!!` keeps the socket WORKING and TCP flow-controls the sender. Nothing
    here is concurrent, so there is no producer to park and no backpressure
    story. That paragraph is not ported, and none of it is claimed below.

  - BYTE ARRAYS. `lf-index` and `concat-bytes` become a scan over an octet view
    and `perturb.octet/oconcat`. The original's `(bit-and (aget a i) 0xff)` has
    no counterpart: `oref` already returns 0..255 (see `perturb.octet`), so
    there is no fold to undo.

  - CHARSETS. `StreamConn` carried a `charset` threaded into `String.` and
    `.getBytes`. `perturb.octet` encodes and decodes UTF-8 and nothing else, so
    the field is deleted rather than kept as a parameter that cannot vary.
    INHERITED I2 rides along: `conn-read-line` builds a host string through
    `perturb.octet/->str`, which throws on a code point above U+FFFF. A line
    containing one is a throw here where the original would have decoded it.

  WHAT THIS NAMESPACE DOES NOT ESTABLISH. It is not concurrent, so nothing here
  says anything about the original's per-connection threading, its worker-pool
  sizing, or the half-close regression its read arity exists to fix — perturb's
  `recv` has no notion of a peer that half-closed while still reading. The one
  property that is actually exercised (`perturb.streamdemo`) is that the line
  scan frames identically no matter where the chunk boundaries fall, including
  one octet per `recv`."
  (:require [perturb.wire :as w]
            [perturb.octet :as o]
            [perturb.effect :as fx]))

(def default-recv-size
  "Octets requested per `recv`. The original's `:buffer-size` was a channel
  depth, which has nothing to be here; this is the read size instead, and it is
  `perturb.nrepl`'s."
  65536)

(def ^:private LF 10)
(def ^:private CR 13)

;; --- the protocol -----------------------------------------------------------

(defprotocol Conn
  (conn-recv [conn]
    "Block for the next incoming octet-view chunk; nil at end of stream.")
  (conn-read-line [conn]
    "Block for the next LF-delimited line (trailing CR/LF stripped); nil at EOF
    (a final unterminated fragment is returned once before nil).")
  (conn-send [conn data]
    "Block until an octet view or host string is fully written, or abort with
    its write failure.")
  (conn-close [conn]
    "Close the connection."))

;; --- scanning ---------------------------------------------------------------

(defn- lf-index
  "Index of the first LF in `ov`, or -1."
  [ov]
  (let [n (o/ocount ov)]
    (loop [i 0]
      (cond (>= i n)             -1
            (= LF (o/oref ov i)) i
            :else                (recur (inc i))))))

(defn- recv-chunk
  "One `recv`, with perturb's end-of-stream convention translated to the
  original's: an empty view means the stream is over, and the original's
  callers spell that `nil`."
  [tok recv-size]
  (let [chunk (w/recv tok recv-size :perturb.stream/recv)]
    (when (pos? (o/ocount chunk)) chunk)))

;; --- the connection ---------------------------------------------------------

(defrecord StreamConn [tok leftover recv-size]
  Conn
  (conn-recv [_] (recv-chunk tok recv-size))

  (conn-send [_ data]
    (let [ov (if (string? data) (o/encode-utf8 data) data)
          n  (w/send! tok ov :perturb.stream/send)]
      ;; The original checks the write completion here and throws its exception.
      ;; The effect boundary already aborts on a handler failure, so what is
      ;; left to check in this spot is the short write.
      (when (not= n (o/ocount ov))
        (fx/abort! :short-write {:perturb.stream/wanted   (o/ocount ov)
                                 :perturb.stream/accepted n}))
      nil))

  (conn-close [_] (w/close! tok :perturb.stream/close))

  (conn-read-line [_]
    (loop []
      (let [lo  @leftover
            idx (lf-index lo)]
        (if (>= idx 0)
          (let [end  (if (and (> idx 0) (= CR (o/oref lo (dec idx)))) (dec idx) idx)
                line (o/->str (o/osub lo 0 end))]
            (reset! leftover (o/osub lo (inc idx) (o/ocount lo)))
            line)
          (if-let [chunk (recv-chunk tok recv-size)]
            (do (reset! leftover (o/oconcat lo chunk)) (recur))
            (when (pos? (o/ocount lo))
              (reset! leftover o/empty-octets)
              (o/->str lo))))))))

(defn conn
  "Wrap an established wire token in a connection. Options: `:recv-size`.

  The token is the handler's, and nothing here inspects it — the same opacity
  `perturb.wire` asks for, which is what lets the scripted handler hand back a
  vector where the posix handler hands back an fd."
  ([tok] (conn tok {}))
  ([tok {:keys [recv-size] :or {recv-size default-recv-size}}]
   (->StreamConn tok (atom o/empty-octets) recv-size)))

(defn connect
  "Establish an outbound connection and return a conn. The client half of the
  lifecycle; `conn-close` is the other end of it."
  ([host port] (connect host port {}))
  ([host port opts]
   (conn (w/connect host port :perturb.stream/connect) opts)))

(defn with-conn
  "Run `(f conn)` and close the connection when it returns or throws; returns
  what `f` returned.

  This is `stream-handler`'s connection arity with the concurrency taken out.
  There the body is `(future (try (f conn) (catch :default _ nil) (finally
  (a/close! in) (tcp/close sock))))`; the `future` is gone because perturb has
  no threads, the `a/close!` is gone with the channel, and the `catch` is gone
  because it existed to keep one connection's failure from killing a pool
  thread. What remains — close in a `finally` whatever `f` did — is the part
  that was about the connection."
  [c f]
  (try (f c)
       (finally (conn-close c))))

;; --- line-oriented use ------------------------------------------------------

(defn drain-lines
  "Read lines until end of stream, as a vector. The final unterminated fragment
  comes back as its own line, once, exactly as `conn-read-line` promises."
  [c]
  (loop [acc []]
    (if-let [line (conn-read-line c)]
      (recur (conj acc line))
      acc)))

(defn read-lines
  "Read exactly `n` lines.

  TEARDOWN IS CONDITIONAL AND IT IS IN THIS BRANCH, which is the shape being
  ported: `jolt.http.protocol` releases the socket next to the decision that
  nothing more can arrive — `(if-not (buf/has-remaining? buffer) (do (tcp/close
  socket) {::step :done}) …)` — rather than unwinding to a common exit. A stream
  that ended with fewer than `n` lines can never produce the rest, so the
  connection is closed here and the caller aborts. The complete-reply arm falls
  out of the loop with the connection still open: that is the keep-alive path
  and it is the caller's to close."
  [c n]
  (loop [i 0 acc []]
    (if (>= i n)
      acc
      (if-let [line (conn-read-line c)]
        (recur (inc i) (conj acc line))
        (do (conn-close c)
            (fx/abort! :truncated {:perturb.stream/wanted n
                                   :perturb.stream/read   (count acc)
                                   :perturb.stream/lines  acc}))))))

(defn request-lines
  "Send `request` and read `n` lines of reply. Returns the lines and leaves the
  connection open."
  [c request n]
  (conn-send c request)
  (read-lines c n))

(defn line-client
  "The whole client lifecycle as one function: connect, write `request`, read
  `n` lines, tear down.

  Exactly one of the two closes in this path runs. A truncated reply is closed
  inside `read-lines` and aborts before reaching the `conn-close` below; a
  complete reply reaches it. That asymmetry is the point — it is the original's,
  not a tidier version of it."
  [host port request n]
  (let [c     (connect host port)
        lines (request-lines c request n)]
    (conn-close c)
    lines))

(defn line-echo
  "The consumer `teensyp`'s own stream tests run against `stream-handler`,
  transcribed: read lines until end of stream, echoing each back wrapped in
  angle brackets."
  [c]
  (loop []
    (when-let [line (conn-read-line c)]
      (conn-send c (str "<" line ">\n"))
      (recur))))

;; --- server side ------------------------------------------------------------

(defn serve-lines
  "Listen, accept `n` connections, and run `(f conn)` on each, closing every
  connection when its `f` returns. Returns the vector of `f`'s results.

  This is what is left of `stream-handler` once the server it plugged into is
  gone. The original returns a 3-arity handler that `teensyp.server` calls back
  — accept arity, read arity, close arity — and hands each connection to a
  worker thread, so many are in flight at once. perturb has no reactor to be
  called back by and no threads, so the shape inverts into a serial accept
  loop: connection i+1 is not accepted until `f` has returned for connection i.
  Nothing here is concurrent and nothing here claims to be.

  The read arity's conditional channel close — `(when (tcp/peer-eof-notified?
  sock) (a/close! (:in state)))` — has no port at all. It exists because the
  reactor could not otherwise tell a blocked consumer that a half-closed peer
  would send nothing more; here the consumer does its own `recv` and sees end of
  stream directly."
  [host port n f]
  (let [lst (w/listen host port :perturb.stream/listen)]
    (try
      (loop [i 0 acc []]
        (if (>= i n)
          acc
          (let [c (conn (w/accept lst :perturb.stream/accept))]
            (recur (inc i) (conj acc (with-conn c f))))))
      (finally (w/close! lst :perturb.stream/close-listener)))))
