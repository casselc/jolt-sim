(ns jolt.sim.posix-loopback-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.net-loopback :as fixture]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]))

(defn- foreign-symbols [effects]
  (set
   (keep (fn [effect]
           (when (= :foreign-function (:kind effect))
             (:symbol effect)))
         effects)))

(deftest unchanged-jolt-net-code-runs-in-the-hermetic-loopback-world
  (let [mem (memory/world)
        world (posix/world mem (net/target-descriptor))
        controlled
        (runtime/run-controlled
         {:ffi-handlers (posix/handlers world)}
         fixture/exercise-loopback)
        result (:result controlled)
        listener (:listener result)
        client-local (get-in result [:client :local])
        client-peer (get-in result [:client :peer])
        server-local (get-in result [:server :local])
        server-peer (get-in result [:server :peer])]
    (is (true? (:pre-accept-would-block? result)))
    (is (= [2 2 1] (:sent-chunks result)))
    (is (= {:bytes [0 1 2 3 255] :chunks [2 2 1]}
           (:received result)))
    (is (true? (:server-eof? result)))
    (is (= [2 1] (:reply-chunks result)))
    (is (= {:bytes [42 0 254] :chunks [2 1]}
           (:reply result)))
    (is (= {:client [true false]
            :server [true false]
            :listener [true false]}
           (:close-results result)))

    ;; The same modeled endpoints are observed from both sides; the selected
    ;; listener and client ports are nonzero and distinct.
    (is (= listener client-peer server-local))
    (is (= client-local server-peer))
    (is (pos? (:jolt.net/port listener)))
    (is (pos? (:jolt.net/port client-local)))
    (is (not= (:jolt.net/port listener)
              (:jolt.net/port client-local)))

    (is (= #{"getaddrinfo" "freeaddrinfo" "socket" "setsockopt"
             "bind" "listen" "fcntl" "getsockname" "getpeername"
             "connect" "accept" "send" "recv" "shutdown" "close"}
           (foreign-symbols (:effects controlled))))
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))
    (is (empty? (posix/snapshot world)))
    (is (empty? (get (posix/state world) :listeners)))
    (is (empty? (get (posix/state world) :addrinfo-allocations)))
    (is (true? (memory/clean? mem)))
    (is (true? (posix/clean? world)))))
