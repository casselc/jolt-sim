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

(defn- throwable-diagnostic [error]
  (let [cause (ex-cause error)]
    (cond->
     {:class (str (class error))
      :message (or (ex-message error) (str error))
      :data (ex-data error)}
      cause
      (assoc :cause
             {:class (str (class cause))
              :message (or (ex-message cause) (str cause))
              :data (ex-data cause)}))))

(defn- future-diagnostic [waiting]
  (if-not (future-done? waiting)
    {:status :pending}
    (try
      {:status :completed :value @waiting}
      (catch :default error
        (assoc (throwable-diagnostic error) :status :failed)))))

(defn- poller-diagnostic [poller]
  (let [wake (:wake poller)
        handle-state
        (fn [handle]
          (select-keys @(:jolt.net/state handle)
                       [:phase :leases :notified?]))]
    {:lifecycle @(:lifecycle poller)
     :wake-admission @(:wake-admission poller)
     :wake-pending @(:wake-pending poller)
     :wake-sequence @(:wake-sequence poller)
     :wake-read (handle-state (:read wake))
     :wake-write (handle-state (:write wake))}))

(defn- barrier-diagnostic [world expected timeout-ms waiting poller]
  {:timeout-ms timeout-ms
   :expected-waiter-count expected
   :readiness (posix/readiness-snapshot world)
   :pipes (posix/pipe-snapshot world)
   :sockets (posix/snapshot world)
   :poller (poller-diagnostic poller)
   :future (future-diagnostic waiting)})

(defn- wait-for-waiter-count
  "Bounded-waits until the modeled readiness waiter-count equals expected.

  Returns true once the exact modeled condition holds. Fails early with
  structured diagnostics if the waiting future realizes before waiter
  registration, and reports bounded readiness/pipe/lifecycle evidence on
  timeout instead of returning false."
  [world expected timeout-ms waiting poller]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [readiness (posix/readiness-snapshot world)]
        (cond
          (= expected (:waiter-count readiness))
          true

          (future-done? waiting)
          (throw
           (ex-info
            "poller await realized before waiter registration"
            (barrier-diagnostic world expected timeout-ms waiting poller)))

          (>= (System/nanoTime) deadline)
          (throw
           (ex-info
            "poller await did not park within the barrier deadline"
            (barrier-diagnostic world expected timeout-ms waiting poller)))

          :else
          (do (Thread/sleep 2) (recur)))))))

(defn- diagnostic-poller []
  (let [handle (fn []
                 {:jolt.net/state
                  (atom {:phase :open :leases 0 :notified? false})})]
    {:lifecycle (atom {:phase :open :awaiting? false})
     :wake-admission (atom {:phase :open :writers 0})
     :wake-pending (atom false)
     :wake-sequence (atom 0)
     :wake {:read (handle) :write (handle)}}))

(deftest waiter-barrier-failures-retain-bounded-diagnostics
  (let [mem (memory/world)
        world (posix/world mem (net/target-descriptor))
        poller (diagnostic-poller)
        completed (future :completed-before-park)
        _ @completed
        completed-data
        (try
          (wait-for-waiter-count world 1 100 completed poller)
          nil
          (catch :default error (ex-data error)))
        failed (future (throw (ex-info "await failed" {:marker :await})))
        _ (try @failed (catch :default _ nil))
        failed-data
        (try
          (wait-for-waiter-count world 1 100 failed poller)
          nil
          (catch :default error (ex-data error)))
        release (promise)
        pending (future @release)
        timeout-data
        (try
          (wait-for-waiter-count world 1 0 pending poller)
          nil
          (catch :default error (ex-data error))
          (finally (deliver release :released) @pending))]
    (is (= {:status :completed :value :completed-before-park}
           (:future completed-data)))
    (is (= :failed (get-in failed-data [:future :status])))
    (is (= {:marker :await}
           (get-in failed-data [:future :cause :data])))
    (is (= 0 (:timeout-ms timeout-data)))
    (is (= {:epoch 0 :waiter-count 0}
           (:readiness timeout-data)))
    (is (= {:phase :open :awaiting? false}
           (get-in timeout-data [:poller :lifecycle])))
    (is (= {:phase :open :writers 0}
           (get-in timeout-data [:poller :wake-admission])))
    (is (= [] (:pipes timeout-data)))
    (is (= [] (:sockets timeout-data)))))

(deftest exceptional-barrier-remains-primary-after-worker-drain
  (let [mem (memory/world)
        world (posix/world mem (net/target-descriptor))
        primary (ex-info "forced poller barrier failure"
                         {:marker :barrier-primary})
        failure
        (try
          (runtime/run-controlled
           {:ffi-handlers (posix/handlers world)}
           #(fixture/exercise-active-poller-close
             (fn [waiting poller]
               (wait-for-waiter-count world 1 10000 waiting poller)
               (throw primary))))
          nil
          (catch :default error error))]
    (is (some? failure))
    (is (identical? primary failure))
    (is (= "forced poller barrier failure" (ex-message failure)))
    (is (= :barrier-primary (:marker (ex-data failure))))
    (is (not (contains?
              (ex-data failure)
              :jolt.sim.fixtures.net-poller/cleanup-errors)))
    (is (zero? (:waiter-count (posix/readiness-snapshot world))))
    (is (empty? (posix/pipe-snapshot world)))
    (is (true? (memory/clean? mem)))
    (is (true? (posix/clean? world)))))

(deftest poller-fixture-cleanup-policy-retains-secondary-errors
  (let [primary (ex-info "primary" {:marker :primary})
        close-error (ex-info "close failed" {:marker :close})
        join-error (ex-info "join failed" {:marker :join})
        cleanup-errors [{:operation :poller-close :error close-error}
                        {:operation :await-join :error join-error}]
        primary-only
        (try
          (fixture/throw-with-cleanup! primary [])
          nil
          (catch :default error error))
        combined
        (try
          (fixture/throw-with-cleanup! primary cleanup-errors)
          nil
          (catch :default error error))
        cleanup-only
        (try
          (fixture/throw-with-cleanup! nil cleanup-errors)
          nil
          (catch :default error error))]
    (is (identical? primary primary-only))
    (is (identical? primary (ex-cause combined)))
    (is (= :primary (:marker (ex-data combined))))
    (is (= [:poller-close :await-join]
           (mapv :operation
                 (:jolt.sim.fixtures.net-poller/cleanup-errors
                  (ex-data combined)))))
    (is (= [:close :join]
           (mapv #(get-in % [:data :marker])
                 (:jolt.sim.fixtures.net-poller/cleanup-errors
                  (ex-data combined)))))
    (is (= :jolt.sim.fixtures.net-poller/cleanup-failure
           (:type (ex-data cleanup-only))))
    (is (identical? close-error (ex-cause cleanup-only)))
    (is (= [:poller-close :await-join]
           (mapv :operation
                 (:jolt.sim.fixtures.net-poller/cleanup-errors
                  (ex-data cleanup-only)))))))

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
           (fn [waiting poller]
             (wait-for-waiter-count world 1 10000 waiting poller))))
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
