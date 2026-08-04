(ns jolt.sim.fixtures.http-hello
  "The exact jolt-http README Hello World application, exercised end-to-end
  through only the public jolt.http.server, teensyp.client, and jolt.net APIs.

  The fixture deliberately knows nothing about jolt-sim. Its caller decides
  whether jolt-http's underlying POSIX calls reach a real host or a simulated
  FFI world."
  (:require [clojure.string :as str]
            [jolt.http.server :as http]
            [teensyp.client :as client]))

(def ^:private hello-body "Hello World")

(defn- hello-handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    hello-body})

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

(defn- parse-response
  "Parses one HTTP/1.1 response into {:status :reason :headers :body}. This is
  a minimal reader for the fixed, unchunked hello-world reply this fixture
  itself requested with `Connection: close` -- not a reimplementation of
  jolt-http's own request parser or conformance rules."
  [raw-bytes]
  (let [raw (String. raw-bytes "UTF-8")
        split-at (str/index-of raw "\r\n\r\n")]
    (when-not split-at
      (throw (ex-info "http-hello fixture response missing header terminator"
                       {:raw raw})))
    (let [head (subs raw 0 split-at)
          body (subs raw (+ split-at 4))
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

(defn exercise-http-hello
  "Runs the exact jolt-http README Hello World server, then drives one
  request/response cycle through it with the public teensyp.client -- itself
  built exclusively on jolt.net. Port 0 selects an ephemeral listener; the
  server's actual bound port is read back from its own public return value."
  []
  (let [server (http/run-server hello-handler
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
