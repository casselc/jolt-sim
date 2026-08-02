(ns jolt.sim.posix-fault-test
  "Focused tests for the first jolt.sim.fault boundary frontend.

  These tests invoke the frontend's poll handler directly through the same
  foreign-function descriptor shape the runtime dispatches, using the same
  inline POSIX target descriptors as jolt.sim.posix-loopback-model-test (no
  jolt.net dependency). The discriminating cases are: first zero-timeout poll
  receives a captured EINTR and the second delegates and returns the ordinary
  result; exact attempt/firing evidence and ordinals; malformed/unsupported
  outcomes and missing target EINTR fail at frontend construction; forged
  worlds, histories, and counters fail closed; empty plan delegates exactly
  once and preserves the handler key set; and concurrent attempts cannot
  duplicate attempt IDs or lose director transitions."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.fault :as fault]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.trace :as trace]))

;; The model-test Linux descriptor lacks :eintr, so it is the exact target for
;; the missing-target-EINTR case. :eintr is added explicitly for the firing
;; cases; Linux EINTR is 4.
(def ^:private linux-descriptor
  {:platform :posix
   :socklen-type :uint
   :nfds-type :size_t
   :sin-len? false
   :const {:af-inet 2 :sock-stream 1
           :sol-socket 1 :so-error 4
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           :f-getfl 3 :f-setfl 4 :o-nonblock 2048
           :pollin 1 :pollout 4 :pollerr 8 :pollhup 16 :pollnval 32}
   :errno {:eagain 11 :einprogress 115 :eaddrinuse 98
           :econnrefused 111 :econnreset 104 :epipe 32}
   :layout {:sockaddr-in {:size 16 :family 0 :port 2 :addr 4}
            :pollfd {:size 8 :fd 0 :events 4 :revents 6}
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8
                       :protocol 12 :addrlen 16 :addr 24
                       :canonname 32 :next 40 :addrlen-type :uint}}})

(def ^:private linux-eintr-descriptor
  (assoc-in linux-descriptor [:errno :eintr] 4))

(def ^:private eintr-plan
  [{:id :f/interrupt-first-poll
    :match {:boundary :posix :operation :poll}
    :activation {:on-match 1 :times 1}
    :outcome {:kind :captured-error :errno :eintr}}])

(defn- frontend-harness
  ([descriptor plan]
   (frontend-harness descriptor plan nil))
  ([descriptor plan config]
   (let [mem (memory/world)
         world (posix/world mem descriptor config)
         frontend (posix-fault/frontend world plan)]
     {:memory mem
      :world world
      :frontend frontend
      :handlers (posix-fault/handlers frontend)})))

(defn- foreign-key [handlers symbol]
  (first (filter #(= symbol (nth % 1 nil)) (keys handlers))))

(defn- foreign-descriptor [handlers symbol arguments]
  (let [key (foreign-key handlers symbol)]
    {:kind :foreign-function
     :task 0
     :symbol symbol
     :argument-types (nth key 2)
     :return-type (nth key 3)
     :blocking? (nth key 4)
     :capture-native-error? (nth key 5)
     :varargs-after (get key 6)
     :arguments (vec arguments)}))

(defn- foreign [h symbol & arguments]
  ((get (:handlers h) (foreign-key (:handlers h) symbol))
   (foreign-descriptor (:handlers h) symbol arguments)))

(defn- poll [h & arguments]
  ((get (:handlers h) (foreign-key (:handlers h) "poll"))
   (foreign-descriptor (:handlers h) "poll" arguments)))

(defn- native [h operation & arguments]
  ((get (:handlers h) [:native-operation operation])
   {:kind :native-operation
    :task 0
    :operation operation
    :arguments (vec arguments)}))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- poll-id [ordinal]
  [:jolt.sim.net.posix-fault/poll ordinal])

(defn- poll-entry [h fd events revents]
  (let [entry (native h :alloc 8)]
    (native h :write entry :int 0 fd)
    (native h :write entry :int16 4 events)
    (native h :write entry :int16 6 revents)
    entry))

(defn- wait-for-waiter-count [world expected timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (= expected (:waiter-count (posix/readiness-snapshot world))) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/yield) (recur))))))

(deftest frontend-rejects-non-world-and-bad-plan-inputs
  (let [world (:world (frontend-harness linux-eintr-descriptor []))]
    (is (= posix-fault/invalid-frontend-input
           (:type (ex-data-of #(posix-fault/frontend :not-a-world [])))))
    (is (= posix-fault/invalid-frontend-input
           (:type (ex-data-of #(posix-fault/frontend world :not-a-plan)))))
    (is (= posix-fault/invalid-frontend-input
           (:type (ex-data-of
                   #(posix-fault/frontend world {:not :a-vector-or-director})))))
    (is (= :plan-or-director-required
           (:reason (ex-data-of #(posix-fault/frontend world :not-a-plan)))))
    (is (= :posix-loopback-world-required
           (:reason (ex-data-of #(posix-fault/frontend :not-a-world [])))))))

(deftest frontend-validates-prebuilt-directors-worlds-and-every-public-entry
  (let [world (:world (frontend-harness linux-eintr-descriptor []))
        valid (posix-fault/frontend world (fault/director eintr-plan))
        forged-director (assoc (fault/director eintr-plan) :firings -1)]
    (is (= fault/invalid-director
           (:type (ex-data-of
                   #(posix-fault/frontend world forged-director)))))
    (doseq [tampered [(dissoc valid :type)
                      (assoc valid :type :forged/frontend)]]
      (doseq [entry [posix-fault/foreign-handlers
                     posix-fault/handlers
                     posix-fault/snapshot
                     posix-fault/evidence-history
                     posix-fault/attempts]]
        (let [data (ex-data-of #(entry tampered))]
          (is (= posix-fault/invalid-frontend-input (:type data)))
          (is (= :frontend-required (:reason data))))))
    (swap! (:state valid) assoc :unknown-state-key true)
    (let [data (ex-data-of #(posix-fault/snapshot valid))]
      (is (= posix-fault/invalid-frontend-input (:type data)))
      (is (= :invalid-frontend-state (:reason data))))
    (is (= :jolt.sim.net.posix-loopback/invalid-world
           (:type
            (ex-data-of
             #(posix-fault/frontend
               {:type :jolt.sim.net.posix-loopback/world} [])))))))

(deftest frontend-rejects-forged-history-and-incoherent-counters
  (let [world (:world (frontend-harness linux-eintr-descriptor []))
        invalid-history (posix-fault/frontend world [])]
    (swap! (:state invalid-history)
           assoc :next-attempt 2
           :history [(trace/canonical-value nil)])
    (let [data (ex-data-of #(posix-fault/evidence-history invalid-history))]
      (is (= posix-fault/invalid-frontend-input (:type data)))
      (is (= :invalid-frontend-history (:reason data)))
      (is (= 1 (:attempt-ordinal data)))))
  (let [world (:world (frontend-harness linux-eintr-descriptor []))
        frontend (posix-fault/frontend world eintr-plan)
        state @(:state frontend)
        stepped
        (fault/step
         (:director state)
         {:attempt-id [:jolt.sim.net.posix-fault/poll 1]
          :boundary :posix
          :operation :poll})]
    (swap! (:state frontend) assoc :director (:director stepped))
    (let [data (ex-data-of #(posix-fault/snapshot frontend))]
      (is (= posix-fault/invalid-frontend-input (:type data)))
      (is (= :incoherent-frontend-state (:reason data))))))

(deftest first-zero-timeout-poll-receives-captured-eintr-then-delegates
  (let [h (frontend-harness linux-eintr-descriptor eintr-plan)]
    ;; A zero-length, zero-timeout poll with no registered fd would normally
    ;; return [0 0]. The fired rule interposes the captured EINTR instead,
    ;; without invoking the modeled poll.
    (is (= [-1 4] (poll h 0 0 0)))
    ;; The single firing is now exhausted, so the next identical poll delegates
    ;; to the original handler and returns its ordinary captured result.
    (is (= [0 0] (poll h 0 0 0)))
    (is (= [0 0] (poll h 0 0 0)))
    (is (= 3 (posix-fault/attempts (:frontend h))))))

(deftest evidence-and-ordinals-are-exact-and-trace-stable
  (let [h (frontend-harness linux-eintr-descriptor eintr-plan)
        _ (poll h 0 0 0)
        _ (poll h 0 0 0)
        history (posix-fault/evidence-history (:frontend h))
        snap (posix-fault/snapshot (:frontend h))]
    (is (= 2 (count history)))
    (let [e1 (nth history 0)
          e2 (nth history 1)]
      (is (= (poll-id 1) (:attempt-id e1)))
      (is (= (poll-id 2) (:attempt-id e2)))
      (is (= #{:attempt-id :matches :firing} (set (keys e1))))
      (is (= :f/interrupt-first-poll (get-in e1 [:matches 0 :rule-id])))
      (is (= 1 (get-in e1 [:matches 0 :match-ordinal])))
      (is (true? (get-in e1 [:matches 0 :activated?])))
      (is (true? (get-in e1 [:matches 0 :fired?])))
      (is (= :f/interrupt-first-poll (get-in e1 [:firing :rule-id])))
      (is (= 1 (get-in e1 [:firing :firing-ordinal])))
      (is (= 1 (get-in e1 [:firing :rule-firing-ordinal])))
      (is (= 1 (get-in e1 [:firing :match-ordinal])))
      (is (= {:kind :captured-error :errno :eintr}
             (get-in e1 [:firing :outcome])))
      (is (nil? (:firing e2)))
      (is (= :f/interrupt-first-poll (get-in e2 [:matches 0 :rule-id])))
      (is (= 2 (get-in e2 [:matches 0 :match-ordinal])))
      (is (false? (get-in e2 [:matches 0 :activated?])))
      (is (false? (get-in e2 [:matches 0 :fired?]))))
    (is (= 3 (:next-attempt snap)))
    (is (= 1 (:firings snap)))
    (is (= [{:rule-id :f/interrupt-first-poll
             :on-match 1 :times 1 :matches 2 :firings 1}]
           (:rules snap)))))

(deftest malformed-and-unsupported-outcomes-fail-at-frontend-construction
  (doseq [[outcome expected-reason]
          [[nil :outcome-must-be-map]
           [:just-a-keyword :outcome-must-be-map]
           [42 :outcome-must-be-map]
           [{:kind :captured-error} :missing-outcome-keys]
           [{:kind :captured-error :errno :eagain}
            :unsupported-captured-errno]
           [{:kind :captured-error :errno :eintr :extra 1}
            :unknown-outcome-keys]
           [{:kind :captured-error :errno :eintr "string-key" 1}
            :unknown-outcome-keys]
           [{:kind :delay :ms 100} :unknown-outcome-keys]
           [{:errno :eintr} :missing-outcome-keys]
           [{} :missing-outcome-keys]]]
    (let [plan [{:id :f/fire-on-every-poll
                 :match {:boundary :posix :operation :poll}
                 :activation {:on-match 1 :times 1}
                 :outcome outcome}]
          mem (memory/world)
          world (posix/world mem linux-eintr-descriptor)
          data (ex-data-of #(posix-fault/frontend world plan))]
      (is (= posix-fault/unsupported-fired-outcome (:type data))
          (pr-str outcome))
      (is (= expected-reason (:reason data)) (pr-str outcome))
      (is (true? (posix/clean? world))))))

(deftest mixed-unknown-outcome-keys-have-stable-typed-diagnostics
  (let [outcome {:kind :captured-error :errno :eintr
                 :extra-keyword 1 "extra-string" 2}
        mem (memory/world)
        world (posix/world mem linux-eintr-descriptor)
        plan [{:id :f/mixed-unknown-keys
               :match {:boundary :posix :operation :poll}
               :activation {:on-match 1}
               :outcome outcome}]
        data (ex-data-of #(posix-fault/frontend world plan))]
    (is (= posix-fault/unsupported-fired-outcome (:type data)))
    (is (= :unknown-outcome-keys (:reason data)))
    (is (= ["extra-string" :extra-keyword] (:unknown data)))))

(deftest rejected-mutable-outcome-cannot-enter-or-alias-history
  (let [source (byte-array [1 2 3])
        mem (memory/world)
        world (posix/world mem linux-eintr-descriptor)
        plan [{:id :f/reject-bytes
               :match {:boundary :posix :operation :poll}
               :activation {:on-match 1}
               :outcome source}]]
    (is (= :outcome-must-be-map
           (:reason
            (ex-data-of #(posix-fault/frontend world plan)))))
    (aset source 0 99)
    (is (= :outcome-must-be-map
           (:reason
            (ex-data-of #(posix-fault/frontend world plan)))))
    (is (true? (posix/clean? world)))))

(deftest fired-eintr-does-not-invoke-or-mutate-the-poll-model
  (let [h (frontend-harness linux-eintr-descriptor eintr-plan)
        entry (poll-entry h 999 0 1234)]
    (try
      (is (= [-1 4] (poll h entry 1 0)))
      ;; Delegation would write POLLNVAL over this caller-owned revents slot.
      (is (= 1234 (native h :read entry :int16 6)))
      (is (zero? (:waiter-count (posix/readiness-snapshot (:world h)))))
      (finally
        (native h :free entry)))))

(deftest missing-target-eintr-fails-closed-at-frontend-construction
  ;; linux-descriptor has no :eintr in its errno map. A fired EINTR outcome
  ;; must fail closed before the modeled poll is invoked.
  (let [mem (memory/world)
        world (posix/world mem linux-descriptor)]
    (doseq [_ (range 2)]
      (let [data
            (ex-data-of #(posix-fault/frontend world eintr-plan))]
        (is (= posix-fault/missing-target-errno (:type data)))
        (is (= :missing-target-eintr (:reason data)))
        (is (= {:errno :eintr} (select-keys data [:errno])))))
    (is (true? (posix/clean? world)))))

(deftest empty-plan-delegates-every-poll-and-preserves-the-handler-key-set
  (let [h (frontend-harness linux-eintr-descriptor [])
        frontend (:frontend h)
        world (:world h)]
    ;; Empty plan never fires: every poll delegates to the ordinary handler.
    (is (= [0 0] (poll h 0 0 0)))
    (is (= [0 0] (poll h 0 0 0)))
    (is (= 2 (posix-fault/attempts frontend)))
    (is (zero? (:firings (posix-fault/snapshot frontend))))
    ;; The exact handler key set is identical to the uninterposed loopback set.
    (is (= (set (keys (posix/foreign-handlers world)))
           (set (keys (posix-fault/foreign-handlers frontend)))))
    (is (= (set (keys (posix/handlers world)))
           (set (keys (posix-fault/handlers frontend)))))
    (is (true? (posix/clean? world))))

  (testing "the interposed poll key keeps its target-exact signature"
    (let [h (frontend-harness linux-eintr-descriptor [])
          frontend (:frontend h)
          base-poll (foreign-key (posix/foreign-handlers (:world h)) "poll")
          fault-poll (foreign-key (posix-fault/foreign-handlers frontend) "poll")]
      (is (= base-poll fault-poll))
      (is (= [:foreign-function "poll" [:pointer :size_t :int] :int true true]
             fault-poll)))))

(deftest empty-plan-delegates-exactly-once-and-non-poll-calls-do-not-step
  (let [h (frontend-harness linux-eintr-descriptor [])
        frontend (:frontend h)
        world (:world h)
        base (posix/foreign-handlers world)
        poll-key (foreign-key base "poll")
        original-poll (get base poll-key)
        delegate-calls (atom 0)]
    (with-redefs [posix/foreign-handlers
                  (fn [_]
                    (assoc base poll-key
                           (fn [descriptor]
                             (swap! delegate-calls inc)
                             (original-poll descriptor))))]
      (let [handlers (posix-fault/foreign-handlers frontend)]
        (is (= [0 0]
               ((get handlers poll-key)
                (foreign-descriptor handlers "poll" [0 0 0]))))
        (is (= 1 @delegate-calls))))
    (let [attempts-before (posix-fault/attempts frontend)
          [fd code] (foreign h "socket" 2 1 0)]
      (is (integer? fd))
      (is (zero? code))
      (is (= attempts-before (posix-fault/attempts frontend)))
      (foreign h "close" fd))
    (is (true? (posix/clean? world)))))

(deftest a-parked-delegated-poll-does-not-hold-the-frontend-lock
  (let [h (frontend-harness linux-eintr-descriptor [])
        world (:world h)
        fds-slot (native h :alloc 8)
        _ (foreign h "pipe" fds-slot)
        read-fd (native h :read fds-slot :int 0)
        write-fd (native h :read fds-slot :int 4)
        entry (poll-entry h read-fd 1 0)
        waiting (future (poll h entry 1 5000))]
    (try
      (is (true? (wait-for-waiter-count world 1 3000)))
      ;; This second call can claim and delegate only if the first parked poll
      ;; released the frontend lock before entering the model wait.
      (is (= [0 0] (poll h 0 0 0)))
      (is (= 2 (posix-fault/attempts (:frontend h))))
      (foreign h "close" write-fd)
      (is (= [1 0] (deref waiting 5000 ::timeout)))
      (finally
        ;; Publish terminal readiness before joining; only then retire memory.
        (foreign h "close" write-fd)
        (deref waiting 5000 ::timeout)
        (foreign h "close" read-fd)
        (native h :free entry)
        (native h :free fds-slot)))))

(deftest a-pre-built-director-is-accepted-and-steps-from-its-current-state
  (let [director (fault/director eintr-plan)
        h (frontend-harness linux-eintr-descriptor director)]
    (is (= [-1 4] (poll h 0 0 0)))
    (is (= [0 0] (poll h 0 0 0)))
    (is (= 1 (:firings (posix-fault/snapshot (:frontend h)))))))

(deftest concurrent-attempts-cannot-duplicate-ordinals-or-lose-transitions
  (let [h (frontend-harness linux-eintr-descriptor eintr-plan)
        frontend (:frontend h)
        concurrency 12
        ready (java.util.concurrent.CountDownLatch. 1)
        armed (java.util.concurrent.CountDownLatch. concurrency)]
    ;; A plan that fires exactly once: across concurrent callers exactly one
    ;; captures EINTR and the rest delegate to the ordinary [0 0].
    (let [futures
          (doall
           (for [_ (range concurrency)]
             (future
               (.countDown armed)
               (.await ready)
               (poll h 0 0 0))))]
      (.await armed)
      (.countDown ready)
      (let [outcomes (doall (map #(deref % 5000 ::timeout) futures))
            history (posix-fault/evidence-history frontend)
            attempt-ids (mapv :attempt-id history)
            snap (posix-fault/snapshot frontend)]
        (is (= concurrency (count outcomes)))
        (is (every? #(not= ::timeout %) outcomes))
        ;; Exactly one caller captured EINTR; the rest delegated to [0 0].
        (is (= 1 (count (filter #{[-1 4]} outcomes))))
        (is (= (dec concurrency) (count (filter #{[0 0]} outcomes))))
        ;; Every per-poll ordinal appears exactly once: no duplicates, no gaps.
        (is (= (set (for [i (range 1 (inc concurrency))] (poll-id i)))
               (set attempt-ids)))
        (is (= concurrency (count attempt-ids)))
        ;; The director transitioned exactly once across all concurrent
        ;; callers: no transition was lost and none was duplicated.
        (is (= 1 (:firings snap)))
        (is (= (inc concurrency) (:next-attempt snap)))
        (is (= 1 (count (filter :firing history))))))))

(deftest frontend-snapshot-and-evidence-carry-no-host-identities
  (let [h (frontend-harness linux-eintr-descriptor eintr-plan)
        _ (poll h 0 0 0)
        snap (posix-fault/snapshot (:frontend h))
        history (posix-fault/evidence-history (:frontend h))]
    ;; Snapshot keys are exactly the stable summary fields.
    (is (= #{:next-attempt :firings :rules} (set (keys snap))))
    (is (= #{:rule-id :on-match :times :matches :firings}
           (set (keys (first (:rules snap)))))
        "snapshot rule entries carry only stable activation/count fields")
    ;; History entries are plain trace-stable evidence maps.
    (is (every? map? history))
    (is (= #{:attempt-id :matches :firing}
           (set (keys (first history)))))
    (is (= :f/interrupt-first-poll
           (get-in (first history) [:firing :rule-id])))
    (is (= history
           (trace/restore-value (trace/canonical-value history))))))
