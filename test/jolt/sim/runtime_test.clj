(ns jolt.sim.runtime-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jolt.sim.runtime :as rt]))

;; A sim-enabled Jolt image exposes jolt.internal.sim; an ordinary released
;; image does not. The ordinary branches prove clean absence while the sim
;; branches exercise event, result, cancellation, and cleanup behavior using
;; ordinary future/promise/atom code. v2 branches additionally exercise FFI
;; interception through jolt.ffi when the image advertises ABI v2.

(def v1-descriptor
  {:abi-version 1
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel]})

(def v2-descriptor
  {:abi-version 2
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel]
   :ffi-interception {:descriptor-version 1
                      :kinds [:foreign-function :native-operation]
                      :arguments :live
                      :task-identity :future-lifecycle
                      :native-operations [:load-library :loaded? :alloc :free
                                          :read :write :sizeof :read-bytes
                                          :write-bytes :read-array :write-array
                                          :ptr->string :string->ptr]}})

(def v3-descriptor
  {:abi-version 3
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel :exit :abort]
   :ffi-interception {:descriptor-version 1
                      :kinds [:foreign-function :native-operation]
                      :arguments :live
                      :task-identity :future-lifecycle
                      :native-operations [:load-library :loaded? :alloc :free
                                          :read :write :sizeof :read-bytes
                                          :write-bytes :read-array :write-array
                                          :ptr->string :string->ptr]}})

;; Binding is safe on every image because native symbol resolution is lazy.
;; Calling this nonexistent symbol is safe only under the ABI v2 controller,
;; where the emitted sim branch must intercept before resolution.
(ffi/defcfn sim-ghost "jolt_sim_ghost_symbol_zzz9" [:int] :int)

;; A defsim scenario defined at namespace load time. Loading must succeed under
;; ordinary Jolt because run-controlled is resolved dynamically at call time.
(rt/defsim sample-scenario {} :scenario-result)

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- ex-of [f]
  (try (f) ::not-thrown (catch :default error error)))

(defn- abi-version
  "Returns the validated descriptor's :abi-version when a sim image is present,
  nil on an ordinary released image."
  []
  (when (rt/available?)
    (:abi-version (rt/capabilities))))

;; Under ABI v3 a normally completed future emits a trailing worker :exit after
;; :finish, and a cancelled future emits :exit after :cancel. v1/v2 stop at the
;; future-settling event.
(defn- expected-finish-events []
  (if (= 3 (abi-version))
    [:spawn :start :finish :exit]
    [:spawn :start :finish]))

(defn- expected-cancel-events []
  (if (= 3 (abi-version))
    [:spawn :start :cancel :exit]
    [:spawn :start :cancel]))

(defn- ffi-capable-version
  "ABI version collapsed for FFI-interception tests: v2 and v3 are equally
  FFI-capable (v3 is the v2 FFI descriptor plus worker-ownership events), so
  both project to :ffi; v1 stays 1; an ordinary image stays nil."
  []
  (let [v (abi-version)]
    (if (#{2 3} v) :ffi v)))

(defn- with-close-signal
  "Runs thunk while wrapping the adapter's private close transition. The
  promise is delivered only after state has become closed, making late-event
  tests causal rather than dependent on sleep timing."
  [closed thunk]
  (let [close-var (resolve 'jolt.sim.runtime/close-state!)
        original @close-var]
    (with-redefs-fn
      {close-var
       (fn [state]
         (let [result (original state)]
           (deliver closed true)
           result))}
      thunk)))

(defn- ordinary-reports-unavailable [thunk]
  (is (false? (rt/available?)))
  (is (= :jolt.sim.runtime/abi-unavailable
         (:type (ex-data-of thunk)))))

(deftest controller-presence-matches-the-running-image
  (if (rt/available?)
    (do
      (is (true? (rt/available?)))
      (is (contains? #{v1-descriptor v2-descriptor v3-descriptor}
                     (rt/capabilities))))
    (ordinary-reports-unavailable rt/capabilities)))

(deftest unimplemented-simulation-options-fail-closed
  (let [data
        (ex-data-of
         #(rt/run-controlled {:seed 7} (fn [] :uncontrolled)))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= [:seed] (:unknown-keys data)))))

(deftest malformed-ffi-handler-keys-fail-closed-before-abi-resolution
  ;; Structural handler validation is pure config checking; it reports before
  ;; ABI resolution on every image, including ordinary released Jolt.
  (let [bad-key [:native-operation :not-a-real-operation]
        data (ex-data-of
              #(rt/run-controlled
                {:ffi-handlers {bad-key (fn [_])}}
                (fn [] :uncontrolled)))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= bad-key (:handler-key data))))
  (let [data (ex-data-of
              #(rt/run-controlled
                {:ffi-handlers {"not-a-vector" (fn [_])}}
                (fn [] :uncontrolled)))]
    (is (= :jolt.sim.runtime/invalid-config (:type data))))
  (let [data (ex-data-of
              #(rt/run-controlled
                {:ffi-handlers {[:foreign-function "s" [:int] :int false] "not-a-fn"}}
                (fn [] :uncontrolled)))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))))

(deftest ffi-handlers-value-nil-is-a-valid-substitute-key
  ;; A nil value is a valid substitute; only malformed shapes are rejected.
  (condp = (ffi-capable-version)
    :ffi (let [result
            (rt/run-controlled
             {:ffi-handlers {[:native-operation :alloc] nil}}
             (fn [] (ffi/alloc 1)))]
        (is (nil? (:result result))))
    1 (is (= :jolt.sim.runtime/capability-unavailable
             (:type
              (ex-data-of
               #(rt/run-controlled
                 {:ffi-handlers {[:native-operation :alloc] nil}}
                 (fn [] :uncontrolled))))))
    nil (ordinary-reports-unavailable
         #(rt/run-controlled
           {:ffi-handlers {[:native-operation :alloc] nil}}
           (fn [] :uncontrolled)))))

(deftest run-controlled-returns-result-and-plain-event-maps
  (if (rt/available?)
    (let [result (rt/run-controlled
                  {}
                  (fn [] (let [worker (future :spawned)] @worker) :done))]
      (is (= :done (:result result)))
      (is (contains? #{v1-descriptor v2-descriptor v3-descriptor} (:capabilities result)))
      (is (and (vector? (:events result))
               (seq (:events result))
               (every? #(= #{:event :task :parent} (set (keys %)))
                       (:events result))))
      (is (= (expected-finish-events) (mapv :event (:events result))))
      (is (apply = (map :task (:events result))))
      (is (every? zero? (map :parent (:events result))))
      ;; :effects is returned only on FFI-capable images (v2/v3).
      (if (#{2 3} (abi-version))
        (is (vector? (:effects result)))
        (is (nil? (:effects result)))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest on-event-may-gate-a-future-before-its-body-starts
  (if (rt/available?)
    (let [started (promise)
          release (promise)
          body-ran? (atom false)
          result (rt/run-controlled
                  {:on-event (fn [event]
                               (when (= :start (:event event))
                                 (deliver started true)
                                 @release))}
                  (fn []
                    (let [worker
                          (future
                            (reset! body-ran? true)
                            :spawned)
                          start-observed
                          (deref started 5000 :timeout)
                          ran-before-release @body-ran?]
                      (deliver release true)
                      {:start-observed start-observed
                       :ran-before-release ran-before-release
                       :worker-result @worker})))]
      (is (true? (get-in result [:result :start-observed])))
      (is (false? (get-in result [:result :ran-before-release])))
      (is (= :spawned (get-in result [:result :worker-result]))))
    (ordinary-reports-unavailable
     #(rt/run-controlled {:on-event (fn [_])} (fn [] :done)))))

(deftest throwing-from-a-start-callback-is-a-controller-error
  (if (rt/available?)
    (let [data
          (ex-data-of
           #(rt/run-controlled
             {:on-event
              (fn [event]
                (when (= :start (:event event))
                  (throw (ex-info "reject start" {:why :controller-failed}))))}
             (fn []
               (let [worker (future :unreachable)]
                 (try @worker (catch :default _ :future-failed))))))]
      (is (= :jolt.sim.runtime/controller-error (:type data)))
      (is (= 1 (count (:errors data))))
      (is (= :start (-> data :errors first :event)))
      (is (= "reject start"
             (-> data :errors first :error :message))))
    (ordinary-reports-unavailable
     #(rt/run-controlled {:on-event (fn [_])} (fn [] :done)))))

(deftest run-controlled-restores-controllers-exactly-for-a-follow-up-run
  (if (rt/available?)
    (let [first-run (rt/run-controlled
                      {} (fn [] (let [w (future :one)] @w) :first))
          second-run (rt/run-controlled
                      {} (fn [] (let [w (future :two)] @w) :second))]
      (is (= :first (:result first-run)))
      (is (= :second (:result second-run)))
      (is (= (expected-finish-events) (mapv :event (:events first-run))))
      (is (= (expected-finish-events) (mapv :event (:events second-run))))
      (is (< (-> first-run :events first :task)
             (-> second-run :events first :task))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest cancellation-is-a-terminal-lifecycle-event
  (if (rt/available?)
    (let [started (promise)
          release (promise)
          worker-body-finished (promise)
          result
          (rt/run-controlled
           {:on-event (fn [event]
                        (when (= :start (:event event))
                          (deliver started true)
                          @release))}
           (fn []
             (let [worker
                   (future
                     (deliver worker-body-finished true)
                     :unreachable)
                   start-observed (deref started 5000 :timeout)
                   cancelled? (future-cancel worker)]
               (deliver release true)
               {:start-observed start-observed
                :cancelled? cancelled?
                :body-finished
                (deref worker-body-finished 5000 :timeout)})))]
      (is (true? (get-in result [:result :start-observed])))
      (is (true? (get-in result [:result :cancelled?])))
      (is (true? (get-in result [:result :body-finished])))
      (is (= (expected-cancel-events)
             (mapv :event (:events result)))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest body-failure-restores-the-controller-and-releases-the-session
  (if (rt/available?)
    (let [body-error (ex-info "body boom" {:phase :run})
          thrown (ex-of #(rt/run-controlled {}
                                     (fn [] (throw body-error) :unreachable)))
          follow-up (rt/run-controlled
                     {} (fn [] (let [w (future :recovered)] @w)))]
      (is (identical? body-error thrown))
      (is (= :recovered (:result follow-up)))
      (is (= (expected-finish-events) (mapv :event (:events follow-up)))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest nested-or-overlapping-runs-are-rejected
  (if (rt/available?)
    (let [inner (ex-data-of
                 #(rt/run-controlled
                   {}
                   (fn [] (rt/run-controlled {} (fn [] :nested)))))]
      (is (= :jolt.sim.runtime/session-overlap (:type inner)))
      (is (map? inner)))
    (ordinary-reports-unavailable
     #(rt/run-controlled {} (fn [] (rt/run-controlled {} (fn [] :nested)))))))

(deftest tasks-spawned-inside-must-stop-before-scope-ends
  ;; Under v1/v2 a task that has not settled before the body returns is
  ;; outliving. Under v3 the same scenario cannot drain (the worker never
  ;; releases ownership), so cleanup refuses to restore and the session is
  ;; poisoned. Defined here but, because it poisons the shared session under
  ;; v3, the v3 branch is skipped inline and exercised by the final test so no
  ;; later test inherits a poisoned session.
  (if (rt/available?)
    (if (= 3 (abi-version))
      (is true "v3 outliving-detection is exercised by the final test")
      (let [latch (promise)
            worker (atom nil)
            data (ex-data-of
                  #(rt/run-controlled
                    {}
                    (fn []
                      (reset! worker (future @latch))
                      :done)))]
        (is (= :jolt.sim.runtime/tasks-outlive-scope (:type data)))
        (is (seq (:tasks data)))
        (deliver latch :late)
        (let [outcome
              (try
                (deref @worker 5000 :timeout)
                (catch :default _ :future-failed))]
          (is (contains? #{:late :future-failed} outcome)))
        (is (= :recovered
               (:result (rt/run-controlled {} (fn [] :recovered)))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest supervisor-latched-terminal-callback-fails-closed
  (if (rt/available?)
    (let [data (ex-data-of
                #(rt/run-controlled
                  {:on-event (fn [event]
                               (when (= :finish (:event event))
                                 (throw (ex-info "gate finish" {:why :denied}))))}
                  (fn [] (let [worker (future :spawned)] @worker) :done)))]
      (is (= :jolt.sim.runtime/controller-error (:type data)))
      (is (= 1 (count (:errors data))))
      (is (= :finish (-> data :errors first :event)))
      (is (= :jolt.sim/exception
             (-> data :errors first :error :kind)))
      (is (= "gate finish"
             (-> data :errors first :error :message)))
      (is (= :recovered
             (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable
     #(rt/run-controlled {:on-event (fn [_])} (fn [] :done)))))

(deftest defsim-defines-a-callable-no-arg-scenario
  (if (rt/available?)
    (let [result (sample-scenario)]
      (is (= :scenario-result (:result result)))
      (is (contains? #{1 2 3} (:abi-version (:capabilities result)))))
    (ordinary-reports-unavailable sample-scenario)))

;; ---- ABI v2 FFI interception ------------------------------------------
;;
;; These tests only exercise real interception when the running image
;; advertises ABI v2. On v1 they assert v1 compatibility (no :effects, FFI
;; handlers rejected); on an ordinary image they assert clean unavailability.

(deftest v1-image-rejects-ffi-handlers-and-omits-effects
  ;; Under v1 an explicitly supplied :ffi-handlers is a capability the image
  ;; does not provide, and the result map never carries :effects.
  (condp = (ffi-capable-version)
    1 (let [data (ex-data-of
                  #(rt/run-controlled
                    {:ffi-handlers {[:native-operation :alloc] (fn [_] 0)}}
                    (fn [] :uncontrolled)))
            plain (rt/run-controlled {} (fn [] :plain))]
        (is (= :jolt.sim.runtime/capability-unavailable (:type data)))
        (is (nil? (:effects plain))))
    :ffi nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest v2-default-native-effect-rejection-before-os-access
  ;; With no handlers configured, every native effect inside the scope is
  ;; rejected before the OS is reached. Direct callers see the typed unhandled
  ;; error; catching it in application code still makes the enclosing run fail
  ;; from the local latch.
  (condp = (ffi-capable-version)
    :ffi (let [load-error
            (ex-of
             #(rt/run-controlled
               {}
               (fn []
                 (ffi/load-library "lib_a_library_that_does_not_exist.so"))))
            ghost-error
            (ex-of #(rt/run-controlled {} (fn [] (sim-ghost 1))))
            swallowed-data
            (ex-data-of
             #(rt/run-controlled
               {}
               (fn []
                 (try
                   (ffi/load-library "lib_still_does_not_exist.so")
                   (catch :default _ :swallowed))
                 :apparently-ok)))]
        (is (= :jolt.sim.runtime/unhandled-native-effect
               (:type (ex-data load-error))))
        (is (= :load-library
               (get-in (ex-data load-error) [:descriptor :operation])))
        (is (= :jolt.sim.runtime/unhandled-native-effect
               (:type (ex-data ghost-error))))
        (is (= "jolt_sim_ghost_symbol_zzz9"
               (get-in (ex-data ghost-error) [:descriptor :symbol])))
        (is (= :jolt.sim.runtime/controller-error (:type swallowed-data)))
        (is (some #(= :unhandled-native-effect (:ffi-error %))
                  (:errors swallowed-data))))
    1 nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest v2-registered-native-and-foreign-substitution-including-nil
  (condp = (ffi-capable-version)
    :ffi (let [result
            (rt/run-controlled
             {:ffi-handlers
              {[:native-operation :alloc]
               (fn [descriptor] 1042)
               [:native-operation :sizeof]
               nil
               [:foreign-function
                "jolt_sim_ghost_symbol_zzz9" [:int] :int false]
               (fn [descriptor] 77)}}
             (fn []
               (let [p (ffi/alloc 8)]
                 [p (ffi/sizeof :int) (sim-ghost 41)])))]
        (is (= [1042 nil 77] (:result result)))
        (let [effects (:effects result)]
          (is (vector? effects))
          (is (some #(and (= :native-operation
                             (:kind %))
                          (= :alloc
                             (:operation %)))
                    effects))
          (is (some #(= :sizeof (:operation %))
                    effects))
          (is (some #(and (= :foreign-function (:kind %))
                          (= "jolt_sim_ghost_symbol_zzz9" (:symbol %))
                          (= [41] (:arguments %)))
                    effects))))
    1 nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest v2-live-byte-array-argument-identity
  ;; Arguments are live in-memory evidence: a byte array handed to a native
  ;; operation is the identical object recorded in :effects.
  (condp = (ffi-capable-version)
    :ffi (let [captured (atom nil)
            result
            (rt/run-controlled
             {:ffi-handlers
              {[:native-operation :write-array]
               (fn [descriptor]
                 (reset! captured (nth (:arguments descriptor) 1))
                 nil)}}
             (fn []
               (let [payload (byte-array [1 2 3 4])]
                 ;; The fake pointer is never dereferenced because write-array
                 ;; is substituted by the handler.
                 (ffi/write-array 1042 payload)
                 payload)))]
        (is (identical? (:result result) @captured))
        (is (some #(identical? (:result result)
                               (-> % :arguments second))
                  (:effects result))))
    1 nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest v2-top-level-task-zero-and-future-task-correlation
  ;; Top-level effects carry :task 0; effects inside a future carry the future
  ;; task id, which matches the :task of that future's lifecycle events.
  (condp = (ffi-capable-version)
    :ffi (let [result
            (rt/run-controlled
             {:ffi-handlers
              {[:native-operation :sizeof] (fn [_] 4)}}
             (fn []
               (ffi/sizeof :int)
               (let [worker (future (ffi/sizeof :long))]
                 @worker)
               :done))
            effects (:effects result)
            events (:events result)
            future-task (-> events first :task)]
        (is (some #(and (zero? (:task %))
                        (= :sizeof (:operation %)))
                  effects))
        (is (some #(= future-task (:task %))
                  effects)))
    1 nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest v2-effect-ordering-matches-interception-arrival
  (condp = (ffi-capable-version)
    :ffi (let [result
            (rt/run-controlled
             {:ffi-handlers
              {[:native-operation :sizeof] (fn [_] 4)
               [:native-operation :alloc] (fn [_] 7)}}
             (fn []
               (ffi/sizeof :int)
               (ffi/alloc 4)
               (ffi/sizeof :long)
               :done))
            ops (mapv :operation (:effects result))]
        (is (= [:sizeof :alloc :sizeof] ops)))
    1 nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest v2-swallowed-handler-failure-still-fails-the-run
  ;; A handler failure is latched locally, so even when application code
  ;; catches the propagated exception the run fails closed.
  (condp = (ffi-capable-version)
    :ffi (let [data
            (ex-data-of
             #(rt/run-controlled
               {:ffi-handlers
                {[:native-operation :alloc]
                 (fn [_]
                   (throw (ex-info "handler boom" {:why :ffi})))}}
               (fn []
                 (try (ffi/alloc 8) (catch :default _ :swallowed))
                 :apparently-ok)))]
        (is (= :jolt.sim.runtime/controller-error (:type data)))
        (is (some #(and (= :handler-error (:ffi-error %))
                        (= "handler boom" (-> % :error :message)))
                  (:errors data))))
    1 nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest v2-restores-ffi-controller-for-a-follow-up-run
  (condp = (ffi-capable-version)
    :ffi (let [first-run
            (rt/run-controlled
             {:ffi-handlers {[:native-operation :sizeof] (fn [_] 4)}}
             (fn [] (ffi/sizeof :int) :first))
            follow-up
            (rt/run-controlled
             {:ffi-handlers {[:native-operation :alloc] (fn [_] 9)}}
             (fn [] (ffi/alloc 8) :second))]
        (is (= :first (:result first-run)))
        (is (= :second (:result follow-up)))
        (is (some #(= :sizeof (:operation %))
                  (:effects first-run)))
        (is (some #(= :alloc (:operation %))
                  (:effects follow-up))))
    1 nil
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

;; ---- ABI v3 worker-ownership lifecycle --------------------------------
;;
;; v3 adds :exit/:abort so cleanup can wait for real worker release. These
;; tests are discriminating only on a v3 image; on v1/v2 they assert that the
;; v3-specific contract does not apply, and on an ordinary image they assert
;; clean unavailability.

(deftest v3-completed-future-releases-worker-at-exit
  (cond
    (= 3 (abi-version))
    (let [result (rt/run-controlled
                  {}
                  (fn [] (let [worker (future :released)] @worker) :done))]
      (is (= :done (:result result)))
      (is (= v3-descriptor (:capabilities result)))
      (is (= [:spawn :start :finish :exit]
             (mapv :event (:events result))))
      (is (apply = (map :task (:events result)))))
      ;; The scripted scheduler (driving ordinary futures from a spawn-ordinal
      ;; script) is intentionally not implemented in this slice.
    (rt/available?)
    (do (is (contains? #{1 2} (abi-version)))
        ;; v1/v2 never emit :exit/:abort; the contract is v3-only.
        (let [run (rt/run-controlled
                   {}
                   (fn [] (let [w (future :x)] @w) :done))]
          (is (= [:spawn :start :finish] (mapv :event (:events run))))))
    :else
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest v3-terminal-cancel-is-distinct-from-worker-exit
  ;; A cancelled running worker settles its future with :cancel but remains
  ;; owned until :exit; cleanup waits for that :exit and the run still succeeds.
  (cond
    (= 3 (abi-version))
    (let [closed (promise)
          body-started (promise)
          result
          (with-close-signal
            closed
            #(rt/run-controlled
              {:drain-timeout-ms 2000}
              (fn []
                (let [worker
                      (future
                        (deliver body-started true)
                        @closed
                        :unreachable)]
                  (when (= :timeout
                           (deref body-started 5000 :timeout))
                    (throw (ex-info "worker body did not start" {})))
                  (future-cancel worker)))))]
      (is (true? (:result result)))
      (is (= [:spawn :start :cancel :exit]
             (mapv :event (:events result)))))
    (rt/available?)
    (is (contains? #{1 2} (abi-version)))
    :else
    (ordinary-reports-unavailable
     #(rt/run-controlled {:on-event (fn [_])} (fn [] :done)))))

(deftest v3-cleanup-waits-for-every-spawned-worker-to-exit
  ;; After a successful v3 run every spawned task must have released worker
  ;; ownership via exactly one :exit or :abort; none may remain owned.
  (cond
    (= 3 (abi-version))
    (let [result
          (rt/run-controlled
           {}
           (fn []
             (let [a (future :a)
                   b (future :b)]
               [@a @b])))]
      (is (= [:a :b] (:result result)))
      (let [events (:events result)
            spawned (set (map :task (filter #(= :spawn (:event %)) events)))
            released (set (map :task (filter #(#{:exit :abort} (:event %))
                                             events)))]
        (is (= spawned released))
        ;; exactly one release event per spawned worker
        (is (= (count spawned)
               (count (filter #(#{:exit :abort} (:event %)) events))))))
    (rt/available?)
    (is (contains? #{1 2} (abi-version)))
    :else
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest drain-timeout-is-an-abi-v3-only-capability
  (cond
    (= 3 (abi-version))
    (is (= :ok
           (:result
            (rt/run-controlled
             {:drain-timeout-ms 100}
             (fn [] :ok)))))
    (rt/available?)
    (is (= :jolt.sim.runtime/capability-unavailable
           (:type
            (ex-data-of
             #(rt/run-controlled
               {:drain-timeout-ms 100}
               (fn [] :uncontrolled))))))
    :else
    (ordinary-reports-unavailable
     #(rt/run-controlled {:drain-timeout-ms 100} (fn [] :done)))))

(deftest v3-cleanup-drains-a-worker-that-finishes-after-the-body
  (cond
    (= 3 (abi-version))
    (let [closed (promise)
          worker-started (promise)
          worker (atom nil)
          result
          (with-close-signal
            closed
            #(rt/run-controlled
              {:drain-timeout-ms 2000}
              (fn []
                (reset! worker
                        (future
                          (deliver worker-started true)
                          @closed
                          :released))
                (when (= :timeout (deref worker-started 5000 :timeout))
                  (throw (ex-info "worker did not start" {})))
                :body-returned)))]
      (is (= :body-returned (:result result)))
      (is (= :released @@worker))
      (is (= [:spawn :start :finish :exit]
             (mapv :event (:events result)))))
    (rt/available?)
    (is (contains? #{1 2} (abi-version)))
    :else
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest v3-restoration-waits-for-an-exit-callback-to-return
  (cond
    (= 3 (abi-version))
    (let [closed (promise)
          worker-started (promise)
          exit-entered (promise)
          release-exit (promise)
          release-observed? (atom false)
          releaser
          (Thread.
           (fn []
             @exit-entered
             (Thread/sleep 75)
             (reset! release-observed? true)
             (deliver release-exit true)))]
      (.start releaser)
      (try
        (let [result
              (with-close-signal
                closed
                #(rt/run-controlled
                  {:drain-timeout-ms 2000
                   :on-event
                   (fn [event]
                     (when (= :exit (:event event))
                       (deliver exit-entered true)
                       @release-exit))}
                  (fn []
                    (let [worker
                          (future
                            (deliver worker-started true)
                            @closed
                            :done)]
                      (when (= :timeout
                               (deref worker-started 5000 :timeout))
                        (throw (ex-info "worker did not start" {})))
                      worker)
                    :body-returned)))]
          (is (= :body-returned (:result result)))
          (is (true? @release-observed?))
          (is (= [:spawn :start :finish :exit]
                 (mapv :event (:events result)))))
        (finally
          (deliver exit-entered true)
          (deliver release-exit true)
          (.join releaser))))
    (rt/available?)
    (is (contains? #{1 2} (abi-version)))
    :else
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest v3-body-failure-drains-workers-before-restoring
  (cond
    (= 3 (abi-version))
    (let [closed (promise)
          worker-started (promise)
          worker (atom nil)
          body-error (ex-info "body boom" {:phase :run})
          thrown
          (ex-of
           #(with-close-signal
              closed
              (fn []
                (rt/run-controlled
                 {:drain-timeout-ms 2000}
                 (fn []
                   (reset! worker
                           (future
                             (deliver worker-started true)
                             @closed
                             :released))
                   (when (= :timeout (deref worker-started 5000 :timeout))
                     (throw (ex-info "worker did not start" {})))
                   (throw body-error))))))]
      (is (identical? body-error thrown))
      (is (= :released @@worker))
      (is (= :recovered
             (:result
              (rt/run-controlled {} (fn [] :recovered))))))
    (rt/available?)
    (is (contains? #{1 2} (abi-version)))
    :else
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest v3-spawn-callback-failure-is-balanced-by-abort
  (cond
    (= 3 (abi-version))
    (let [data
          (ex-data-of
           #(rt/run-controlled
             {:on-event
              (fn [event]
                (when (= :spawn (:event event))
                  (throw (ex-info "reject spawn" {:why :test}))))}
             (fn []
               (try
                 (future :unreachable)
                 (catch :default _ :caught))
               :apparently-ok)))]
      (is (= :jolt.sim.runtime/controller-error (:type data)))
      (is (= [:spawn :abort] (mapv :event (:events data))))
      (is (= :recovered
             (:result (rt/run-controlled {} (fn [] :recovered))))))
    (rt/available?)
    (is (contains? #{1 2} (abi-version)))
    :else
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest v3-late-nested-spawn-is-rejected-balanced-and-drained
  (cond
    (= 3 (abi-version))
    (let [closed (promise)
          parent-started (promise)
          parent (atom nil)
          data
          (ex-data-of
           #(with-close-signal
              closed
              (fn []
                (rt/run-controlled
                 {:drain-timeout-ms 2000}
                 (fn []
                   (reset! parent
                           (future
                             (deliver parent-started true)
                             @closed
                             (try
                               (future :late-child)
                               (catch :default _ :child-rejected))))
                   (when (= :timeout (deref parent-started 5000 :timeout))
                     (throw (ex-info "parent did not start" {})))
                   :body-returned)))))]
      (is (= :jolt.sim.runtime/controller-error (:type data)))
      (is (= [:spawn :start :spawn :abort :finish :exit]
             (mapv :event (:events data))))
      (is (= :child-rejected @@parent))
      (is (= :recovered
             (:result
              (rt/run-controlled {} (fn [] :recovered))))))
    (rt/available?)
    (is (contains? #{1 2} (abi-version)))
    :else
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))
