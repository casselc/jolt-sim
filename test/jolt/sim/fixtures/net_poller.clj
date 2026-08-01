(ns jolt.sim.fixtures.net-poller
  "Ordinary jolt.net application code used unchanged with a handler pack.

  The fixture deliberately knows nothing about jolt-sim. Its caller decides
  whether the public socket and poller calls reach a real host or a simulated
  FFI world."
  (:require [jolt.net :as net]))

(defn- write-all! [socket bytes]
  (loop [offset 0 chunks []]
    (if (= offset (alength bytes))
      chunks
      (let [written
            (net/try-write-bytes!
             socket bytes offset (- (alength bytes) offset))]
        (cond
          (net/would-block? written)
          (throw
           (ex-info "poller fixture write unexpectedly would block"
                    {:offset offset :length (alength bytes)}))

          (pos? written)
          (recur (+ offset written) (conj chunks written))

          :else
          (throw
           (ex-info "poller fixture write made no progress"
                    {:offset offset :result written})))))))

(defn- read-exact! [socket length]
  (let [dest (byte-array length)]
    (loop [offset 0 chunks []]
      (if (= offset length)
        {:bytes (vec dest) :chunks chunks}
        (let [read
              (net/try-read-bytes! socket dest offset (- length offset))]
          (cond
            (net/would-block? read)
            (throw
             (ex-info "poller fixture read unexpectedly would block"
                      {:offset offset :length length}))

            (net/eof? read)
            (throw
             (ex-info "poller fixture reached EOF before the requested bytes"
                      {:offset offset :length length}))

            (pos? read)
            (recur (+ offset read) (conj chunks read))

            :else
            (throw
             (ex-info "poller fixture read made no progress"
                      {:offset offset :result read}))))))))

(defn exercise-poller
  "Exercise one IPv4 stream plus a poller through only the public jolt.net API.

  Evidence covers token succession and currentness, read/write readiness, the
  wake-cursor publication contract, partial byte I/O, and idempotent ownership
  cleanup. await-ready is the operation that reaches the target's native poll."
  []
  (let [listener
        (net/listen
         (net/endpoint "127.0.0.1" 0)
         {:reuse-address? true})
        poller* (atom nil)
        client* (atom nil)
        server* (atom nil)]
    (try
      (let [listener-endpoint (net/local-endpoint listener)
            client (net/connect listener-endpoint)
            _ (reset! client* client)
            server (net/try-accept listener)
            _ (when (net/would-block? server)
                (throw
                 (ex-info "poller fixture connection was not available to accept"
                          {:listener listener-endpoint})))
            _ (reset! server* server)
            poller (net/open-poller)
            _ (reset! poller* poller)
            token (net/register! poller server #{:read})
            idle (net/await-ready poller 0)
            payload (byte-array [0 1 2 3 255])
            sent (write-all! client payload)
            readable (net/await-ready poller 1000)
            received (read-exact! server (alength payload))
            successor (net/update-registration! poller token #{:write})
            writable (net/await-ready poller 1000)
            removed (net/remove-registration! poller successor)
            post-remove (net/await-ready poller 0)
            wake-before (net/wake-cursor poller)
            _ (net/wake! poller)
            wake-after (net/wake-cursor poller)
            woken (net/await-ready poller 0 wake-before)
            poller-close [(net/close! poller) (net/close! poller)]
            server-close [(net/close! server) (net/close! server)]
            client-close [(net/close! client) (net/close! client)]
            listener-close [(net/close! listener) (net/close! listener)]]
        {:listener listener-endpoint
         :native-handles
         {:server (net/native-handle server)
          :client (net/native-handle client)}
         :register-token token
         :idle-before-write idle
         :sent-chunks sent
         :readable readable
         :received received
         :successor-token successor
         :token-succeeded? (not= token successor)
         :writable writable
         :removed removed
         :post-remove post-remove
         :wake-cursor-before wake-before
         :wake-cursor-after wake-after
         :wake-advanced? (> wake-after wake-before)
         :woken woken
         :close-results
         {:poller poller-close
          :server server-close
          :client client-close
          :listener listener-close}})
      (finally
        (when-let [poller @poller*] (net/close! poller))
        (when-let [server @server*] (net/close! server))
        (when-let [client @client*] (net/close! client))
        (net/close! listener)))))

(defn exercise-active-poller-close
  "Park an ordinary public await, then close its poller from the owning thread.

  `await-parked!` is a generic test barrier supplied by the caller. It returns
  only after the await has entered the native boundary; the fixture itself still
  imports no simulator namespace and uses no simulator value."
  [await-parked!]
  (let [poller (net/open-poller)
        cursor (net/wake-cursor poller)
        waiting (future (net/await-ready poller 5000 cursor))]
    (try
      (when-not (await-parked!)
        (throw (ex-info "public poller await did not reach its native wait"
                        {:timeout-ms 5000})))
      (let [first-close (net/close! poller)
            awaited (deref waiting 5000 ::await-timeout)
            second-close (net/close! poller)]
        {:parked? true
         :awaited awaited
         :await-completed? (not= ::await-timeout awaited)
         :close-results [first-close second-close]})
      (finally
        (net/close! poller)
        (future-cancel waiting)))))
