(ns jolt.sim.posix-loopback-http-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.http-hello :as fixture]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]))

(defn- foreign-symbols [effects]
  (set
   (keep (fn [effect]
           (when (= :foreign-function (:kind effect))
             (:symbol effect)))
         effects)))

(deftest unchanged-jolt-http-hello-world-runs-in-the-hermetic-loopback-world
  (let [mem (memory/world)
        world (posix/world mem (net/target-descriptor) {:pipe-capacity 1})
        controlled
        (runtime/run-controlled
         {:ffi-handlers (posix/handlers world)
          :drain-timeout-ms 10000}
         fixture/exercise-http-hello)
        result (:result controlled)
        parsed (:parsed result)]
    (is (pos? (:port result)))
    (is (= 200 (:status parsed)))
    (is (= "OK" (:reason parsed)))
    (is (= "Hello World" (:body parsed)))
    (is (= "text/plain; charset=utf-8"
           (get (:headers parsed) "content-type")))
    (is (= "close" (get (:headers parsed) "connection")))
    (is (= (str (count "Hello World"))
           (get (:headers parsed) "content-length")))
    (is (some? (get (:headers parsed) "date")))
    (is (nil? (:sent-result result)))
    (is (pos? (:raw-length result)))
    (is (= {:connection [true false]} (:close-results result)))

    ;; The reactor's own listener/client/server descriptors, the self-pipe
    ;; wake pair, and the poll(2) readiness loop all crossed the same
    ;; hermetic boundary -- nothing routed to a real socket.
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))
    (let [symbols (foreign-symbols (:effects controlled))]
      (is (= #{"accept" "bind" "close" "connect" "fcntl" "freeaddrinfo"
               "getaddrinfo" "getpeername" "getsockname" "listen" "pipe"
               "poll" "read" "recv" "send" "getsockopt" "setsockopt"
               "socket" "write"}
             symbols)))

    (let [info (:connection-info result)]
      (is (= :open (:state info)))
      (is (false? (:closed? info)))
      (is (true? (:read-eof? info)))
      (is (false? (:write-shutdown? info)))
      (is (= "127.0.0.1" (get-in info [:local-address :host])))
      (is (= :inet (get-in info [:local-address :family])))
      (is (= "127.0.0.1" (get-in info [:remote-address :host])))
      (is (= :inet (get-in info [:remote-address :family])))
      (is (= (:port result) (get-in info [:remote-address :port])))
      (is (pos? (get-in info [:local-address :port])))
      (is (not= (get-in info [:local-address :port])
                (get-in info [:remote-address :port]))))

    ;; Everything the reactor, executors, poller, and connections opened is
    ;; closed and joined in this same fresh process before this assertion.
    (is (empty? (posix/snapshot world)))
    (is (empty? (posix/pipe-snapshot world)))
    (is (zero? (:waiter-count (posix/readiness-snapshot world))))
    (is (empty? (get (posix/state world) :listeners)))
    (is (empty? (get (posix/state world) :addrinfo-allocations)))
    (is (true? (memory/clean? mem)))
    (is (true? (posix/clean? world)))
    ;; The unchanged HTTP stack remains correct at one-byte self-pipe capacity.
    ;; The focused ordinary-poller fixture owns the deterministic EAGAIN claim;
    ;; an active HTTP reactor may drain between close's two wake attempts.
    (let [summary (posix/pipe-capacity-summary world)]
      (is (= 1 (:pipe-capacity summary)))
      (is (= 1 (:max-pipe-fifo-bytes summary))))))
