(ns jolt.sim.fixtures.http-sqlite
  "Ordinary jolt-http + db.sqlite application code used unchanged against real
  sockets plus real SQLite and the hermetic POSIX loopback + SQLite handler
  pack.

  This namespace deliberately knows nothing about jolt-sim. It depends only on
  public jdbc.core, jolt.http.server, teensyp.client, and basic Clojure. Its
  caller decides whether the underlying POSIX and SQLite foreign calls reach
  the host or a simulated FFI world.

  The request handler opens an in-memory SQLite database. Opening that
  connection runs \"PRAGMA foreign_keys=1;\" via the db.sqlite connection
  initialization before any explicit statement is prepared. The handler then
  creates one table, inserts a BLOB whose octets span the signed/unsigned byte
  boundary, reads that BLOB back, and returns the fetched byte array verbatim
  as an application/octet-stream HTTP body. The response framing is parsed by
  scanning raw bytes for the header terminator, so the binary body survives
  byte-exact while only the headers are decoded."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [teensyp.client :as client]))

(def ^:private blob-octets [0 65 127 128 255])

(defn- blob-handler [_request]
  (with-open [connection (jdbc/connection "sqlite::memory:")]
    ;; Opening the connection runs "PRAGMA foreign_keys=1;" via the db.sqlite
    ;; connection initialization before the first explicit statement below.
    (jdbc/execute!
     connection
     "create table sim_blob (id integer primary key, payload blob)")
    (jdbc/execute!
     connection
     ["insert into sim_blob (id, payload) values (?, ?)"
      1
      (byte-array blob-octets)])
    (let [row (jdbc/fetch-one
               connection
               ["select payload from sim_blob where id = ?" 1])]
      {:status  200
       :headers {"Content-Type" "application/octet-stream"}
       :body    (:payload row)})))

(defn- request-bytes [host port]
  (.getBytes
   (str "GET / HTTP/1.1\r\n"
        "Host: " host ":" port "\r\n"
        "Connection: close\r\n"
        "\r\n")
   "UTF-8"))

(defn- copy-of-length ^bytes [^bytes src n]
  (let [dest (byte-array n)]
    (System/arraycopy src 0 dest 0 n)
    dest))

(defn- copy-range ^bytes [^bytes src start end]
  (let [n (- end start)
        dest (byte-array n)]
    (System/arraycopy src start dest 0 n)
    dest))

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        dest (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (do
          (System/arraycopy chunk 0 dest offset (alength chunk))
          (recur (rest remaining) (+ offset (alength chunk))))
        dest))))

(defn- read-response-until-eof!
  "Reads every byte the server sends until it closes, via only the public
  teensyp.client blocking API."
  [connection]
  (let [scratch (byte-array 4096)]
    (loop [chunks []]
      (let [n (client/receive-into! connection scratch 0 (alength scratch)
                                     {:timeout-ms 5000})]
        (if (nil? n)
          (concat-byte-arrays chunks)
          (recur (conj chunks (copy-of-length scratch n))))))))

(defn- ubyte [^bytes arr i]
  (bit-and 0xFF (int (aget arr i))))

(defn- find-header-terminator
  "Returns the byte index of the first \\r\\n\\r\\n sequence in raw, or nil.
  Scanning raw bytes keeps the search independent of any body decoding."
  [^bytes raw]
  (let [n (alength raw)]
    (loop [i 0]
      (if (> (+ i 4) n)
        nil
        (if (and (= 13 (ubyte raw i))
                 (= 10 (ubyte raw (+ i 1)))
                 (= 13 (ubyte raw (+ i 2)))
                 (= 10 (ubyte raw (+ i 3))))
          i
          (recur (inc i)))))))

(defn- parse-response
  "Parses one HTTP/1.1 response into {:status :reason :headers :body}. Only the
  fixed response framing is parsed: the headers are decoded as UTF-8 while the
  body is preserved as the exact raw byte array -- this is a minimal reader for
  the fixed, unchunked reply this fixture itself requested with
  `Connection: close`, not a general HTTP parser."
  [^bytes raw]
  (let [terminator (find-header-terminator raw)]
    (when-not terminator
      (throw (ex-info "http-sqlite fixture response missing header terminator"
                      {:raw-length (alength raw)})))
    (let [head-bytes (copy-range raw 0 terminator)
          head (String. head-bytes "UTF-8")
          body (copy-range raw (+ terminator 4) (alength raw))
          [status-line & header-lines] (str/split head #"\r\n")
          [_ status reason] (re-matches #"HTTP/1\.1 (\d{3}) (.*)" status-line)
          headers (into {}
                        (map (fn [line]
                               (let [colon (str/index-of line ":")]
                                 [(str/lower-case (subs line 0 colon))
                                  (str/trim (subs line (inc colon)))])))
                        header-lines)]
      {:status  (Long/parseLong status)
       :reason  reason
       :headers headers
       :body    body})))

(defn exercise-http-sqlite
  "Runs a jolt-http server whose synchronous handler opens an in-memory SQLite
  database, inserts and reads back a BLOB spanning the signed/unsigned byte
  boundary, and serves the fetched octets as an application/octet-stream body.
  One request/response cycle is driven through the public teensyp.client --
  itself built exclusively on jolt.net. Port 0 selects an ephemeral listener;
  the server's actual bound port is read back from its own public return
  value."
  []
  (let [server (http/run-server blob-handler
                                 :port 0
                                 :reuse-address? true)
        connection* (atom nil)]
    (try
      (let [port (:port server)
            connection (client/connect "127.0.0.1" port
                                        {:connect-timeout-ms 5000})
            _ (reset! connection* connection)
            request (request-bytes "127.0.0.1" port)
            sent (client/send-all! connection request {:timeout-ms 5000})
            raw (read-response-until-eof! connection)
            parsed (parse-response raw)]
        {:port port
         :sent-result sent
         :raw-length (alength raw)
         :parsed parsed
         :connection-info (client/connection-info connection)
         :close-results
         {:connection [(client/close! connection) (client/close! connection)]}})
      (finally
        (when-let [connection @connection*]
          (client/close! connection))
        (http/stop-server server)))))
