(ns jolt.sim.posix-loopback-poller-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.net-poller :as fixture]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]))

(def ^:private interrupt-second-poll-plan
  [{:id :poller/interrupt-second-poll
    :match {:boundary :posix :operation :poll}
    :activation {:on-match 2 :times 1}
    :outcome {:kind :captured-error :errno :eintr}}])

(defn- foreign-symbols [effects]
  (set
   (keep (fn [effect]
           (when (= :foreign-function (:kind effect))
             (:symbol effect)))
         effects)))

(defn- wait-for-waiter-count [world expected timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (= expected (:waiter-count (posix/readiness-snapshot world))) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/yield) (recur))))))

(deftest unchanged-jolt-net-poller-code-runs-in-the-hermetic-loopback-world
  (let [mem (memory/world)
        world (posix/world mem (net/target-descriptor) {:pipe-capacity 1})
        fault-frontend (posix-fault/frontend world interrupt-second-poll-plan)
        controlled
        (runtime/run-controlled
         {:ffi-handlers (posix-fault/handlers fault-frontend)}
         fixture/exercise-poller)
        result (:result controlled)
        listener (:listener result)
        token (:register-token result)
        successor (:successor-token result)]
    (is (map? token))
    (is (true? (:token-succeeded? result)))
    (is (not= token successor))

    (is (= [] (:idle-before-write result)))
    (is (= [{:token token :events #{:read}}] (:readable result)))
    (is (= [2 2 1] (:sent-chunks result)))
    (is (= {:bytes [0 1 2 3 -1] :chunks [2 2 1]}
           (:received result)))
    (is (= [{:token successor :events #{:write}}]
           (:writable result)))

    (is (true? (:removed result)))
    (is (= [] (:post-remove result)))
    (is (true? (:wake-advanced? result)))
    (is (pos? (- (:wake-cursor-after result)
                 (:wake-cursor-before result))))
    (is (= [] (:woken result)))

    (let [server-handle (get-in result [:native-handles :server])
          client-handle (get-in result [:native-handles :client])]
      (is (integer? server-handle))
      (is (integer? client-handle))
      (is (not= server-handle client-handle)))
    (is (pos? (:jolt.net/port listener)))

    (is (= {:poller [true false]
            :server [true false]
            :client [true false]
            :listener [true false]}
           (:close-results result)))

    (let [symbols (foreign-symbols (:effects controlled))]
      (is (contains? symbols "poll"))
      (is (every? #(contains? symbols %) ["pipe" "read" "write"])))
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))

    ;; The fixture's first await is the intentional zero-timeout idle probe.
    ;; The second native poll belongs to the positive-timeout readable wait:
    ;; it receives captured EINTR, and unchanged pinned jolt.net retries through
    ;; the same FFI boundary before producing the readable result above.
    (let [fault-snapshot (posix-fault/snapshot fault-frontend)
          history (posix-fault/evidence-history fault-frontend)
          firing (get-in history [1 :firing])]
      (is (> (posix-fault/attempts fault-frontend) 2))
      (is (= 1 (:firings fault-snapshot)))
      (is (= [:jolt.sim.net.posix-fault/poll 2]
             (:attempt-id (nth history 1))))
      (is (= :poller/interrupt-second-poll (:rule-id firing)))
      (is (= 2 (:match-ordinal firing)))
      (is (= {:kind :captured-error :errno :eintr} (:outcome firing)))
      (is (= 1 (count (keep :firing history)))))

    (is (empty? (posix/snapshot world)))
    (is (empty? (posix/pipe-snapshot world)))
    (is (zero? (:waiter-count (posix/readiness-snapshot world))))
    (is (empty? (get (posix/state world) :listeners)))
    (is (empty? (get (posix/state world) :sockets)))
    (is (empty? (get (posix/state world) :pipes)))
    (is (empty? (get (posix/state world) :addrinfo-allocations)))
    (is (true? (memory/clean? mem)))
    (is (true? (posix/clean? world)))
    ;; The self-pipe wake FIFO is capped at one byte. The pinned public poller
    ;; first publishes its acknowledged close mutation and then deliberately
    ;; attempts an unconditional terminal wake. Exact evidence proves that
    ;; ordinary code reached the full-pipe EAGAIN path, accepted it as an
    ;; already-pending wake, and never exceeded the bound.
    (let [summary (posix/pipe-capacity-summary world)]
      (is (= 1 (:pipe-capacity summary)))
      (is (pos? (:pipe-would-blocks summary)))
      (is (= 1 (:max-pipe-fifo-bytes summary))))))

(deftest active-public-poller-close-wakes-and-joins-a-parked-native-wait
  (let [mem (memory/world)
        world (posix/world mem (net/target-descriptor))
        controlled
        (runtime/run-controlled
         {:ffi-handlers (posix/handlers world)}
         #(fixture/exercise-active-poller-close
           (fn [] (wait-for-waiter-count world 1 3000))))
        result (:result controlled)
        symbols (foreign-symbols (:effects controlled))]
    (is (true? (:parked? result)))
    (is (true? (:await-completed? result)))
    (is (= [] (:awaited result)))
    (is (= [true false] (:close-results result)))
    (is (every? #(contains? symbols %) ["pipe" "poll" "write" "read"]))
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))
    (is (empty? (posix/pipe-snapshot world)))
    (is (zero? (:waiter-count (posix/readiness-snapshot world))))
    (is (true? (memory/clean? mem)))
    (is (true? (posix/clean? world)))))
