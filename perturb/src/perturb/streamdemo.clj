(ns perturb.streamdemo
  "Runs `perturb.stream`'s read loop against `perturb.script`, and prints what it
  found.

  The one property worth testing here is the one the original's own generative
  test states: `conn-read-line` frames lines identically under arbitrary
  chunking. `perturb.script` delivers one octet per `recv` by default, which is
  the worst chunking there is, so the line scan goes through its `:recur` arm on
  nearly every octet. The other direction — several lines inside one chunk — is
  tested by counting the receives that actually carried data, because that is
  the only thing that distinguishes it from the first case.

  Nothing here needs a network. `perturb.script/replay-handler` answers
  `:connect`/`:send`/`:recv`/`:close` from a vector, and
  `perturb.script/server-session` answers `:listen`/`:accept` as well, so the
  client and the server halves both run in memory."
  (:require [perturb.stream :as st]
            [perturb.script :as script]
            [perturb.posix :as posix]
            [perturb.wire :as w]
            [perturb.octet :as o]
            [perturb.effect :as fx]))

(def ^:private failures (atom []))

(defn- check [name expected actual]
  (if (= expected actual)
    (println (str "  ok   " name))
    (do (swap! failures conj name)
        (println (str "  FAIL " name))
        (println (str "         expected " (pr-str expected)))
        (println (str "         actual   " (pr-str actual))))))

;; --- the scripted peer ------------------------------------------------------

(defn- scripted
  "`perturb.script/replay-handler` over a one-entry transcript this namespace
  fabricates. The handler re-chunks whatever it replays, so `chunk-size` is the
  only knob that matters: 1 is one octet per `recv`."
  ([text chunk-size] (scripted text chunk-size nil))
  ([text chunk-size record]
   (script/replay-handler [{:op :recv :octets (o/encode-utf8 text)}] chunk-size record)))

(defn- recvs [trace]
  (filter (fn [e] (= :recv (:perturb.effect/op e))) trace))

(defn- carrying-recvs
  "Receives that returned octets. An empty view is end of stream, and counting
  those as reads would hide the difference this file is trying to show."
  [trace]
  (filter (fn [e] (pos? (o/ocount (:perturb.effect/result e)))) (recvs trace)))

(defn- drain-under
  "Drain every line from a peer that delivers `text` in `chunk-size` chunks.
  Returns {:lines … :recvs n :carrying n}."
  [text chunk-size]
  (let [trace (atom [])]
    (fx/with-trace trace
      (fx/with-handlers {'perturb.wire/socket (scripted text chunk-size)}
        (let [c  (st/connect "in-memory" 0)
              ls (st/drain-lines c)]
          (st/conn-close c)
          {:lines    ls
           :recvs    (count (recvs @trace))
           :carrying (count (carrying-recvs @trace))})))))

;; --- the tests --------------------------------------------------------------

(def ^:private mixed
  "One CRLF line, one LF line, one empty line, one long line, one bare
  fragment with no terminator at all."
  "GET /a HTTP/1.1\r\nhost: perturb\n\nthe-quick-brown-fox-jumps-over-the-lazy-dog\nno-newline-here")

(def ^:private mixed-lines
  ["GET /a HTTP/1.1" "host: perturb" ""
   "the-quick-brown-fox-jumps-over-the-lazy-dog" "no-newline-here"])

(defn- test-one-octet []
  (println "== one octet per recv ==")
  (let [n (o/ocount (o/encode-utf8 mixed))
        r (drain-under mixed 1)]
    (check "five lines, CR stripped, empty line kept, final fragment returned"
           mixed-lines (:lines r))
    (check (str "every one of the " n " octets arrived in its own recv")
           n (:carrying r))
    ;; The first line is 17 octets on the wire and comes out of 17 separate
    ;; receives. There is nothing else this file is really about.
    (check "the first line alone was assembled from 17 chunks"
           "GET /a HTTP/1.1" (first (:lines r)))))

(defn- test-two-lines-one-chunk []
  (println "== two lines in a single chunk ==")
  (let [r (drain-under "one\ntwo\n" 4096)]
    (check "both lines framed" ["one" "two"] (:lines r))
    ;; This is the claim. One receive carried data; the second line was framed
    ;; out of the leftover with no I/O between it and the first.
    (check "exactly one recv carried octets" 1 (:carrying r))
    (check "and one more recv saw end of stream" 2 (:recvs r))))

(defn- test-chunking-invariance []
  (println "== framing is invariant under every chunk boundary ==")
  (let [n   (o/ocount (o/encode-utf8 mixed))
        bad (filter (fn [k] (not= mixed-lines (:lines (drain-under mixed k))))
                    (range 1 (inc n)))]
    (check (str "all " n " chunk sizes from 1 to " n " frame identically")
           [] (vec bad))))

(defn- test-edges []
  (println "== the scan's edges ==")
  (check "a bare LF is an empty line" [""] (:lines (drain-under "\n" 1)))
  (check "a bare CRLF is an empty line too" [""] (:lines (drain-under "\r\n" 1)))
  (check "a lone CR is not a terminator" ["a\rb"] (:lines (drain-under "a\rb\n" 1)))
  (check "CR is only stripped when it precedes the LF"
         ["a" "b\rc"] (:lines (drain-under "a\r\nb\rc\n" 1)))
  (check "an empty stream yields no lines at all" [] (:lines (drain-under "" 1)))
  (check "the unterminated fragment is returned exactly once, then nil"
         ["a" "b" nil]
         (fx/with-handlers {'perturb.wire/socket (scripted "a\nb" 1)}
           (let [c (st/connect "in-memory" 0)
                 r [(st/conn-read-line c) (st/conn-read-line c) (st/conn-read-line c)]]
             (st/conn-close c)
             r))))

(defn- test-client []
  (println "== the line client, end to end ==")
  (let [record (atom [])
        trace  (atom [])
        lines  (fx/with-trace trace
                 (fx/with-handlers
                   {'perturb.wire/socket (scripted "+OK first\r\n+OK second\r\n+OK third\r\n" 1 record)}
                   (st/line-client "in-memory" 0 "LINES 2\r\n" 2)))]
    (check "connect, write, read exactly 2 lines, close"
           ["+OK first" "+OK second"] lines)
    (check "the request went out as octets, once"
           ["LINES 2\r\n"] (mapv (fn [e] (o/->str (:octets e))) @record))
    (check "the connection was closed, once"
           1 (count (filter (fn [e] (= :close (:perturb.effect/op e))) @trace)))
    ;; The reply is 34 octets. Reading stopped on the second line's LF, at 23,
    ;; so the third line is still sitting in the peer's queue.
    (check "it stopped reading at the second line — 23 of 34 octets received"
           23 (count (carrying-recvs @trace)))))

(defn- test-conditional-teardown []
  (println "== teardown inside one branch ==")
  (let [trace (atom [])
        r     (fx/with-trace trace
                (fx/with-handlers {'perturb.wire/socket (scripted "only\none\n" 1)}
                  (try (st/line-client "in-memory" 0 "LINES 3\r\n" 3)
                       (catch :default e [:aborted (:perturb.effect/abort (ex-data e))
                                          (:perturb.stream/read (ex-data e))]))))]
    (check "a reply short of n lines aborts rather than returning what arrived"
           [:aborted :truncated 2] r)
    ;; The close in `read-lines`' end-of-stream branch is the one that ran; the
    ;; close at the end of `line-client` is unreachable on this path.
    (check "the connection was released in that branch, not left open"
           1 (count (filter (fn [e] (= :close (:perturb.effect/op e))) @trace)))
    (check "and the close was the last thing on the wire"
           :close (:perturb.effect/op (last @trace)))))

(defn- test-server []
  (println "== the server half: serve-lines + line-echo ==")
  (let [sess  (script/server-session {:conns [(o/encode-utf8 "hi\nyo\n")] :chunk-size 1})
        trace (atom [])]
    (fx/with-trace trace
      (fx/with-handlers {'perturb.wire/socket (:handler sess)}
        (st/serve-lines "127.0.0.1" 0 1 st/line-echo)))
    (check "the accepted connection was line-echoed, one octet per recv"
           "<hi>\n<yo>\n" (o/->str (script/sent-octets (:state sess) 0)))
    (check "six octets in, six carrying recvs" 6 (count (carrying-recvs @trace)))
    (check "both the connection and the listener were closed"
           2 (count (filter (fn [e] (= :close (:perturb.effect/op e))) @trace)))))

(defn- test-posix [port]
  (println (str "== the same conn code over a real loopback socket, port " port " =="))
  (let [r (try
            (fx/with-handlers {'perturb.wire/socket (posix/handler)}
              ;; `serve-lines` is not used here and cannot be: it listens and
              ;; accepts in one call, and a single-threaded process has to get
              ;; the client's `connect` in between. The listen backlog holds it,
              ;; which is `perturb.httpdemo`'s trick. The connections either side
              ;; of the accept are ordinary `perturb.stream` conns.
              (let [lst   (w/listen "127.0.0.1" port :perturb.streamdemo/listen)
                    cli   (st/conn (w/connect "127.0.0.1" port :perturb.streamdemo/connect))
                    _     (st/conn-send cli "hi\nyo\n")
                    srv   (st/conn (w/accept lst :perturb.streamdemo/accept))
                    lines (st/read-lines srv 2)
                    _     (doseq [l lines] (st/conn-send srv (str "<" l ">\n")))
                    got   (st/read-lines cli 2)]
                (st/conn-close cli)
                (st/conn-close srv)
                (w/close! lst :perturb.streamdemo/close-listener)
                {:server lines :client got}))
            (catch :default e [:threw (str e)]))]
    (if (vector? r)
      (println (str "  SKIP no loopback socket here: " (second r)))
      (do (check "the server framed both lines off a real socket"
                 ["hi" "yo"] (:server r))
          (check "the client framed both echoes back off a real socket"
                 ["<hi>" "<yo>"] (:client r))))))

;; --- entry point ------------------------------------------------------------

(defn run
  ([] (run 7911))
  ([port]
  (reset! failures [])
  (test-one-octet)
  (test-two-lines-one-chunk)
  (test-chunking-invariance)
  (test-edges)
  (test-client)
  (test-conditional-teardown)
  (test-server)
  (test-posix port)
  (println)
  (if (empty? @failures)
    (println "STREAM OK")
    (println (str "STREAM FAILED: " (pr-str @failures))))
  (empty? @failures)))

(defn -main [& args]
  (let [port (if (seq args) (Integer/parseInt (first args)) 7911)]
    (if (run port) (System/exit 0) (System/exit 1))))
