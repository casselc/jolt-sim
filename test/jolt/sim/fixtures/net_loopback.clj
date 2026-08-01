(ns jolt.sim.fixtures.net-loopback
  "Ordinary jolt.net application code used unchanged with a handler pack.

  The fixture deliberately knows nothing about jolt-sim.  Its caller decides
  whether the public socket calls reach a real host or a simulated FFI world."
  (:require [jolt.net :as net]))

(defn- write-all!
  [socket bytes]
  (loop [offset 0 chunks []]
    (if (= offset (alength bytes))
      chunks
      (let [written
            (net/try-write-bytes!
             socket bytes offset (- (alength bytes) offset))]
        (cond
          (net/would-block? written)
          (throw
           (ex-info "loopback fixture write unexpectedly would block"
                    {:offset offset :length (alength bytes)}))

          (pos? written)
          (recur (+ offset written) (conj chunks written))

          :else
          (throw
           (ex-info "loopback fixture write made no progress"
                    {:offset offset :result written})))))))

(defn- read-exact!
  [socket length]
  (let [dest (byte-array length)]
    (loop [offset 0 chunks []]
      (if (= offset length)
        {:bytes (vec dest) :chunks chunks}
        (let [read
              (net/try-read-bytes! socket dest offset (- length offset))]
          (cond
            (net/would-block? read)
            (throw
             (ex-info "loopback fixture read unexpectedly would block"
                      {:offset offset :length length}))

            (net/eof? read)
            (throw
             (ex-info "loopback fixture reached EOF before the requested bytes"
                      {:offset offset :length length}))

            (pos? read)
            (recur (+ offset read) (conj chunks read))

            :else
            (throw
             (ex-info "loopback fixture read made no progress"
                      {:offset offset :result read}))))))))

(defn exercise-loopback
  "Exercises one complete IPv4 stream through only the public jolt.net API.

  The listener uses port zero, accept is probed before and after connect, both
  endpoint directions are inspected, byte slices must make partial progress,
  and a client write-half-close produces server EOF without disabling reverse
  traffic.  Cleanup calls the public close operation twice to prove its owned
  handle contract is idempotent."
  []
  (let [listener
        (net/listen
         (net/endpoint "127.0.0.1" 0)
         {:reuse-address? true})
        client* (atom nil)
        server* (atom nil)]
    (try
      (let [listener-endpoint (net/local-endpoint listener)
            pre-accept (net/try-accept listener)
            client (net/connect listener-endpoint)
            _ (reset! client* client)
            server (net/try-accept listener)
            _ (reset! server* server)]
        (when (net/would-block? server)
          (throw (ex-info "loopback connection was not available to accept"
                          {:listener listener-endpoint})))
        (let [payload (byte-array [0 1 2 3 255])
              reply (byte-array [42 0 254])
              client-local (net/local-endpoint client)
              client-peer (net/peer-endpoint client)
              server-local (net/local-endpoint server)
              server-peer (net/peer-endpoint server)
              sent (write-all! client payload)
              received (read-exact! server (alength payload))
              _ (net/shutdown! client :write)
              eof-result (net/try-read-bytes! server (byte-array 1) 0 1)
              replied (write-all! server reply)
              reply-result (read-exact! client (alength reply))]
          {:pre-accept-would-block? (net/would-block? pre-accept)
           :listener listener-endpoint
           :client {:local client-local :peer client-peer}
           :server {:local server-local :peer server-peer}
           :sent-chunks sent
           :received received
           :server-eof? (net/eof? eof-result)
           :reply-chunks replied
           :reply reply-result
           :close-results
           {:client [(net/close! client) (net/close! client)]
            :server [(net/close! server) (net/close! server)]
            :listener [(net/close! listener) (net/close! listener)]}}))
      (finally
        (when-let [server @server*] (net/close! server))
        (when-let [client @client*] (net/close! client))
        (net/close! listener)))))
