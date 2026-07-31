(ns jolt.sim.runtime-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jolt.sim.runtime :as rt]))

;; A sim-enabled Jolt image exposes jolt.internal.sim; an ordinary released
;; image does not. The ordinary branches prove clean absence while the sim
;; branches exercise the one exact current controller contract using ordinary
;; future/promise/atom code and jolt.ffi interception.

;; This full literal independently pins production's current descriptor. During
;; prerelease development a future ABI bump replaces this contract in place;
;; it does not add another accepted compatibility descriptor.
(def supported-descriptor
  {:abi-version 5
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel :exit :abort]
   :ffi-interception {:descriptor-version 4
                      :kinds [:foreign-function :native-operation]
                      :arguments :live
                      :task-identity :future-lifecycle
                      :native-operations [:load-library :loaded? :alloc :free
                                          :read :write :sizeof :read-bytes
                                          :write-bytes :read-array :write-array
                                          :borrow-byte-array :release-byte-array
                                          :ptr->string :string->ptr]
                      :proceed-routing {:controller-arity 2
                                        :proceed-arity 0
                                        :single-use true
                                        :dynamic-extent true
                                        :owner-thread true
                                        :scoped-byte-array-release :runtime-owned}}})

;; Binding is safe on every image because native symbol resolution is lazy.
;; Calling this nonexistent symbol is safe only under the sim controller,
;; where the emitted branch must intercept before native resolution.
(ffi/defcfn sim-ghost "jolt_sim_ghost_symbol_zzz9" [:int] :int)

;; A defsim scenario defined at namespace load time. Loading must succeed under
;; ordinary Jolt because run-controlled is resolved dynamically at call time.
(rt/defsim sample-scenario {} :scenario-result)

(def scheduled-scenario-order (atom []))

(rt/defsim scheduled-scenario
  {}
  (reset! scheduled-scenario-order [])
  (let [first-worker
        (future
          (swap! scheduled-scenario-order conj :first)
          :first)
        second-worker
        (future
          (swap! scheduled-scenario-order conj :second)
          :second)]
    {:values [@first-worker @second-worker]
     :start-order @scheduled-scenario-order}))

(def configurable-scenario-events (atom []))

(rt/defsim configurable-scenario
  {:on-event
   (fn [event]
     (swap! configurable-scenario-events
            conj [:declared (:event event)]))}
  (let [worker (future :configured)]
    @worker))

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- ex-of [f]
  (try (f) ::not-thrown (catch :default error error)))

(defn- expected-finish-events []
  [:spawn :start :finish :exit])

(defn- expected-cancel-events []
  [:spawn :start :cancel :exit])

(defn- ffi-capable-version
  "Returns :ffi for the exact current sim image, nil for ordinary Jolt."
  []
  (when (rt/available?) :ffi))

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
      (is (= supported-descriptor (rt/capabilities))))
    (ordinary-reports-unavailable rt/capabilities)))

(deftest unimplemented-simulation-options-fail-closed
  (let [data
        (ex-data-of
         #(rt/run-controlled {:seed 7} (fn [] :uncontrolled)))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= [:seed] (:unknown-keys data)))))

(deftest ffi-mode-config-fails-closed-before-abi-resolution
  (doseq [mode [nil :transparent :real "observe" 4]]
    (let [data
          (ex-data-of
           #(rt/run-controlled {:ffi-mode mode} (fn [] :uncontrolled)))]
      (is (= :jolt.sim.runtime/invalid-config (:type data)) (pr-str mode))
      (is (= mode (:ffi-mode data)) (pr-str mode))))
  ;; Even an empty explicit handler map contradicts observe mode. This is
  ;; rejected before capability lookup on ordinary and custom images alike.
  (let [data
        (ex-data-of
         #(rt/run-controlled
           {:ffi-mode :observe :ffi-handlers {}}
           (fn [] :uncontrolled)))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= :observe (:ffi-mode data))))
  (let [data (ex-data-of #(rt/modeled-resource 10 0))]
    (is (= :jolt.sim.runtime/invalid-modeled-resource (:type data)))))

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
      (is (= supported-descriptor (:capabilities result)))
      (is (and (vector? (:events result))
               (seq (:events result))
               (every? #(= #{:event :task :parent} (set (keys %)))
                       (:events result))))
      (is (= (expected-finish-events) (mapv :event (:events result))))
      (is (apply = (map :task (:events result))))
      (is (every? zero? (map :parent (:events result))))
      (is (vector? (:effects result)))
      (is (vector? (:effect-trace result)))
      (is (not (contains? result :schedule-events))))
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
  ;; A worker that never releases ownership cannot drain, so cleanup refuses to
  ;; restore and poisons the shared session. The destructive control lives in
  ;; the isolated runtime-poison suite; this unit test merely reserves it.
  (if (rt/available?)
    (is true "outliving detection is exercised by the isolated poison suite")
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
  (is (true? (:jolt.sim/scenario (meta #'sample-scenario))))
  (if (rt/available?)
    (let [result (sample-scenario)]
      (is (= :scenario-result (:result result)))
      (is (= 5 (:abi-version (:capabilities result)))))
    (ordinary-reports-unavailable sample-scenario)))

(deftest defsim-runtime-overrides-require-a-map-before-abi-resolution
  (let [data (ex-data-of #(sample-scenario [:not-a-map]))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= 'sample-scenario (:scenario data)))
    (is (= [:not-a-map] (:runtime-overrides data)))))

(deftest defsim-runtime-overrides-can-drive-the-future-scheduler
  (if (rt/available?)
    (let [run (scheduled-scenario {:future-schedule [1 0]})]
      (is (= [:first :second] (get-in run [:result :values])))
      (is (= [:second :first] (get-in run [:result :start-order])))
      (is (= [[:admit 1] [:complete 1]
              [:admit 0] [:complete 0]]
             (:schedule-events run))))
    (ordinary-reports-unavailable
     #(scheduled-scenario {:future-schedule [1 0]}))))

(deftest defsim-runtime-overrides-merge-over-the-declared-config
  (if (rt/available?)
    (do
      (reset! configurable-scenario-events [])
      (let [run
            (configurable-scenario
             {:on-event
              (fn [event]
                (swap! configurable-scenario-events
                       conj [:override (:event event)]))})]
        (is (= :configured (:result run)))
        (is (= (mapv (fn [event] [:override event])
                     (expected-finish-events))
               @configurable-scenario-events)))
      (reset! configurable-scenario-events [])
      (let [run
            (configurable-scenario {:future-schedule [0]})]
        (is (= :configured (:result run)))
        (is (= (mapv (fn [event] [:declared event])
                     (expected-finish-events))
               @configurable-scenario-events))
        (is (= [[:admit 0] [:complete 0]]
               (:schedule-events run)))))
    (ordinary-reports-unavailable
     #(configurable-scenario {:on-event (fn [_])}))))

;; ---- Current FFI interception -----------------------------------------
;;
;; These tests exercise interception on the exact current sim image and clean
;; unavailability on ordinary released Jolt.

(deftest default-native-effect-rejection-before-os-access
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
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest registered-native-and-foreign-substitution-including-nil
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
                    effects))
          (is (= effects (mapv :descriptor (:effect-trace result))))
          (is (every? #(= :hermetic (:mode %)) (:effect-trace result)))
          (is (every? #(= :handler (:route %)) (:effect-trace result)))))
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest live-byte-array-argument-identity
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
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest top-level-task-zero-and-future-task-correlation
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
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest effect-ordering-matches-interception-arrival
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
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest swallowed-handler-failure-still-fails-the-run
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
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

(deftest restores-ffi-controller-for-a-follow-up-run
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
    nil (ordinary-reports-unavailable
         #(rt/run-controlled {} (fn [] :done)))))

;; ---- Current descriptor and handler contracts -------------------------

(def ^:private validate-descriptor-var
  (resolve 'jolt.sim.runtime/validate-descriptor))

(def ^:private validate-ffi-descriptor-var
  (resolve 'jolt.sim.runtime/validate-ffi-descriptor!))

(def ^:private validate-ffi-handlers-var
  (resolve 'jolt.sim.runtime/validate-ffi-handlers!))

(def ^:private descriptor-handler-key-var
  (resolve 'jolt.sim.runtime/descriptor-handler-key))

(def ^:private make-ffi-controller-var
  (resolve 'jolt.sim.runtime/make-ffi-controller))

(def ^:private make-ffi-routing-controller-var
  (resolve 'jolt.sim.runtime/make-ffi-routing-controller))

(deftest exact-current-capability-descriptor-is-accepted-and-mismatches-rejected
  ;; Pure structural validation, independent of the running image.
  (is (= supported-descriptor
         (validate-descriptor-var supported-descriptor)))
  (is (= supported-descriptor
         @(resolve 'jolt.sim.runtime/supported-descriptor)))
  ;; Prerelease versions are exact: stale and future ABI numbers are rejected
  ;; rather than accumulating compatibility branches.
  (doseq [version [3 4 6 7]]
    (let [bad (assoc supported-descriptor :abi-version version)
          data (ex-data-of #(validate-descriptor-var bad))]
      (is (= :jolt.sim.runtime/abi-incompatible (:type data))
          (pr-str version))))
  (let [bad (assoc-in supported-descriptor
                      [:ffi-interception :proceed-routing :controller-arity] 3)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (assoc-in supported-descriptor
                      [:ffi-interception :proceed-routing :extra] :nope)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (assoc-in supported-descriptor
                      [:ffi-interception :descriptor-version] 2)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [short
        (assoc-in supported-descriptor [:ffi-interception :native-operations]
                  (vec (remove #{:release-byte-array}
                               (get-in supported-descriptor
                                       [:ffi-interception :native-operations]))))
        data (ex-data-of #(validate-descriptor-var short))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [reordered
        (assoc-in supported-descriptor [:ffi-interception :native-operations]
                  [:load-library :loaded? :alloc :free
                   :read :write :sizeof :read-bytes
                   :write-bytes :read-array :write-array
                   :release-byte-array :borrow-byte-array
                   :ptr->string :string->ptr])
        data (ex-data-of #(validate-descriptor-var reordered))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (dissoc supported-descriptor :controller-errors)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data)))))

(def ^:private base-foreign-function-descriptor
  {:kind :foreign-function :task 0 :arguments [0]
   :symbol "s" :argument-types [:int] :return-type :int :blocking? false})

(deftest ffi-descriptor-shape-is-exact-for-the-current-contract
  (let [descriptor (assoc base-foreign-function-descriptor
                          :capture-native-error? true)]
    (is (= descriptor (validate-ffi-descriptor-var descriptor))))
  (let [data (ex-data-of
              #(validate-ffi-descriptor-var base-foreign-function-descriptor))]
    (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data)))
    (is (= :foreign-function-key-mismatch (:reason data))))
  (let [bad (assoc base-foreign-function-descriptor
                    :capture-native-error? "nope")
        data (ex-data-of #(validate-ffi-descriptor-var bad))]
    (is (= :invalid-capture-native-error (:reason data))))
  (let [bad (assoc base-foreign-function-descriptor
                   :arguments [] :capture-native-error? false)
        data (ex-data-of #(validate-ffi-descriptor-var bad))]
    (is (= :argument-count-mismatch (:reason data))))
  (doseq [op (get-in supported-descriptor
                     [:ffi-interception :native-operations])]
    (let [op-descriptor {:kind :native-operation :task 0 :arguments []
                         :operation op}]
      (is (= op-descriptor (validate-ffi-descriptor-var op-descriptor))
          (pr-str op))))
  (let [bad {:kind :native-operation :task 0 :arguments []
             :operation :not-current}
        data (ex-data-of #(validate-ffi-descriptor-var bad))]
    (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data)))
    (is (= :unknown-operation (:reason data)))))

(deftest ffi-handler-keys-accept-five-element-shorthand-and-canonical-six-element-forms
  (let [five-key [:foreign-function "s" [:int] :int false]
        handler (fn [_] :ok)
        canonical (validate-ffi-handlers-var {five-key handler})]
    (is (= {(conj five-key false) handler} canonical)))
  (let [six-key [:foreign-function "s" [:int] :int false true]
        handler (fn [_] :ok)]
    (is (= {six-key handler} (validate-ffi-handlers-var {six-key handler})))))

(deftest ffi-handler-keys-recognize-current-native-operations
  ;; Handler config validation is pure config checking that runs before ABI
  ;; resolution, so it recognizes the two current pointer-loan
  ;; operations as supported handler keys; a still-unknown operation fails
  ;; closed.
  (let [handler (fn [_] :loan)]
    (is (= {[:native-operation :borrow-byte-array] handler}
           (validate-ffi-handlers-var
            {[:native-operation :borrow-byte-array] handler})))
    (is (= {[:native-operation :release-byte-array] handler}
           (validate-ffi-handlers-var
            {[:native-operation :release-byte-array] handler}))))
  (let [unknown [:native-operation :still-not-an-operation]
        data (ex-data-of
              #(validate-ffi-handlers-var {unknown (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= unknown (:handler-key data)))))

(deftest malformed-six-element-handler-keys-are-rejected
  (let [bad-capture [:foreign-function "s" [:int] :int false "yes"]
        data (ex-data-of
              #(validate-ffi-handlers-var {bad-capture (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= bad-capture (:handler-key data))))
  (let [too-long [:foreign-function "s" [:int] :int false true true]
        data (ex-data-of
              #(validate-ffi-handlers-var {too-long (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))))

(deftest ambiguous-shorthand-and-six-element-false-handler-keys-are-rejected
  (let [five-key [:foreign-function "s" [:int] :int false]
        six-key (conj five-key false)
        data (ex-data-of
              #(validate-ffi-handlers-var {five-key (fn [_] :a)
                                           six-key (fn [_] :b)}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= [six-key] (:ambiguous-keys data))))
  ;; The shorthand together with the six-element-*true* key is a distinct
  ;; signature (different capture identity), so it is never ambiguous.
  (let [five-key [:foreign-function "s" [:int] :int false]
        six-key-true (conj five-key true)
        canonical (validate-ffi-handlers-var {five-key (fn [_] :a)
                                              six-key-true (fn [_] :b)})]
    (is (= #{(conj five-key false) six-key-true} (set (keys canonical))))))

(deftest five-element-handler-shorthand-matches-capture-false-descriptor
  (let [five-key [:foreign-function "s" [:int] :int false]
        handlers (validate-ffi-handlers-var {five-key (fn [_] :matched)})
        descriptor (assoc base-foreign-function-descriptor
                          :capture-native-error? false)
        state (atom {:ffi-errors []})
        effects (atom [])
        controller (make-ffi-controller-var handlers state effects)]
    (is (= (conj five-key false)
           (descriptor-handler-key-var descriptor)))
    (is (= :matched (controller descriptor)))
    (is (empty? (:ffi-errors @state)))))

;; ---- Recursive foreign argument-type identity (descriptor-version 4) ----
;;
;; A public argument type is a primitive keyword or exactly
;; [:by-value [:struct [[field-name field-type] ...]]]. Structs are nonempty,
;; field names are unqualified keywords, nested field types are primitive
;; keywords or a bare nested [:struct ...], and a nested :by-value wrapper is
;; invalid. These are pure structural tests, independent of the running image.

(def ^:private flat-struct-type
  [:by-value [:struct [[:x :int] [:y :pointer]]]])

(def ^:private nested-struct-type
  [:by-value [:struct [[:tag :int]
                      [:point [:struct [[:x :int] [:y :int]]]]]]])

(defn- struct-foreign-descriptor
  "Builds a valid foreign-function descriptor whose single argument has the
  supplied argument type."
  ([argument-type]
   (struct-foreign-descriptor argument-type false))
  ([argument-type capture?]
   (-> base-foreign-function-descriptor
       (assoc :argument-types [argument-type]
              :capture-native-error? capture?))))

(deftest recursive-struct-argument-types-are-valid-ffi-descriptors
  (is (= (struct-foreign-descriptor flat-struct-type)
         (validate-ffi-descriptor-var
          (struct-foreign-descriptor flat-struct-type))))
  (is (= (struct-foreign-descriptor nested-struct-type true)
         (validate-ffi-descriptor-var
          (struct-foreign-descriptor nested-struct-type true))))
  ;; Primitive and struct argument types coexist, and the argument count still
  ;; must match :arguments length.
  (let [mixed (-> base-foreign-function-descriptor
                  (assoc :argument-types [:int flat-struct-type]
                         :arguments [0 1]
                         :capture-native-error? false))]
    (is (= mixed (validate-ffi-descriptor-var mixed)))))

(deftest malformed-struct-argument-types-are-rejected
  (let [reject
        (fn [argument-type]
          (let [descriptor (struct-foreign-descriptor argument-type)
                data (ex-data-of #(validate-ffi-descriptor-var descriptor))]
            (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data))
                (pr-str argument-type))
            (is (= :invalid-argument-types (:reason data))
                (pr-str argument-type))))]
    ;; empty struct
    (reject [:by-value [:struct []]])
    ;; bare top-level struct without the :by-value wrapper
    (reject [:struct [[:x :int]]])
    ;; nested :by-value wrapper is invalid as a field type
    (reject [:by-value [:struct [[:x [:by-value [:struct [[:a :int]]]]]]]])
    ;; qualified field name
    (reject [:by-value [:struct [[:ns/x :int]]]])
    ;; qualified structural tags
    (reject [:ns/by-value [:struct [[:x :int]]]])
    (reject [:by-value [:ns/struct [[:x :int]]]])
    (reject [:by-value [:struct [[:x [:ns/struct [[:a :int]]]]]]])
    ;; non-keyword field name
    (reject [:by-value [:struct [["x" :int]]]])
    ;; field type is an unrecognized vector form (neither keyword nor :struct)
    (reject [:by-value [:struct [[:x [:bogus [:int]]]]]])
    ;; :by-value not wrapping a [:struct ...] shape
    (reject [:by-value :int])
    ;; struct shape of the wrong arity
    (reject [:by-value [:struct]])))

(deftest struct-argument-types-share-a-canonical-handler-key-by-shape
  (let [desc-a (struct-foreign-descriptor flat-struct-type)
        desc-b (struct-foreign-descriptor flat-struct-type)
        different [:by-value [:struct [[:x :int] [:z :pointer]]]]
        desc-c (struct-foreign-descriptor different)
        key-a (descriptor-handler-key-var desc-a)
        key-c (descriptor-handler-key-var desc-c)]
    ;; Nested shape identity: structurally-equal shapes share one key, while a
    ;; single renamed field yields a distinct key.
    (is (= key-a (descriptor-handler-key-var desc-b)))
    (is (not= key-a key-c))
    (is (= :foreign-function (nth key-a 0)))
    (is (= [flat-struct-type] (nth key-a 2)))
    ;; A nested struct preserves its full recursive identity in the key.
    (is (= [nested-struct-type]
           (nth (descriptor-handler-key-var
                 (struct-foreign-descriptor nested-struct-type))
                2)))
    ;; The struct-bearing key dispatches through the hermetic controller.
    (let [state (atom {:ffi-errors []})
          effects (atom [])
          controller (make-ffi-controller-var
                      {key-a (fn [_] :matched)}
                      state effects)]
      (is (= :matched (controller desc-a)))
      (is (= [desc-a] (mapv :descriptor @effects)))
      (is (empty? (:ffi-errors @state))))))

(deftest struct-argument-types-are-accepted-as-foreign-handler-keys
  (let [key [:foreign-function "make_point" [flat-struct-type]
             :pointer true false]
        handler (fn [_] :ok)]
    (is (= {key handler} (validate-ffi-handlers-var {key handler})))
    ;; A malformed struct inside a handler key is rejected as bad config.
    (let [bad-key [:foreign-function "make_point"
                   [[:by-value [:struct []]]] :pointer true false]
          data (ex-data-of #(validate-ffi-handlers-var {bad-key (fn [_])}))]
      (is (= :jolt.sim.runtime/invalid-config (:type data)))
      (is (= bad-key (:handler-key data))))))

(deftest same-signature-scalar-and-captured-handlers-do-not-collide
  (let [scalar-descriptor (assoc base-foreign-function-descriptor
                                 :capture-native-error? false)
        captured-descriptor (assoc base-foreign-function-descriptor
                                   :capture-native-error? true)
        scalar-key (descriptor-handler-key-var scalar-descriptor)
        captured-key (descriptor-handler-key-var captured-descriptor)
        state (atom {:ffi-errors []})
        effects (atom [])
        controller (make-ffi-controller-var
                    {scalar-key (fn [_] 42)
                     captured-key (fn [_] [42 nil])}
                    state effects)]
    (is (not= scalar-key captured-key))
    (is (= 42 (controller scalar-descriptor)))
    (is (= [42 nil] (controller captured-descriptor)))
    (is (empty? (:ffi-errors @state)))))

(deftest current-controller-dispatches-pointer-loan-and-captured-foreign
  ;; The current FFI controller accepts the two pointer-loan
  ;; native operations and routes them to their handlers, still accepts every
  ;; base operation, and enforces the current capture-result contract on
  ;; captured foreign functions.
  (let [loan-descriptor {:kind :native-operation :task 0 :arguments [7]
                         :operation :borrow-byte-array}
        release-descriptor {:kind :native-operation :task 0 :arguments [7]
                            :operation :release-byte-array}
        base-descriptor {:kind :native-operation :task 0 :arguments [4]
                         :operation :alloc}
        captured-descriptor (assoc base-foreign-function-descriptor
                                   :capture-native-error? true)]
    (let [state (atom {:ffi-errors []})
          effects (atom [])
          controller (make-ffi-controller-var
                      {[:native-operation :borrow-byte-array] (fn [_] :borrowed)
                       [:native-operation :release-byte-array] (fn [_] :released)
                       [:native-operation :alloc] (fn [_] 1042)
                       (descriptor-handler-key-var captured-descriptor)
                       (fn [_] [99 nil])}
                      state effects)]
      (is (= :borrowed (controller loan-descriptor)))
      (is (= :released (controller release-descriptor)))
      (is (= 1042 (controller base-descriptor)))
      (is (= [99 nil] (controller captured-descriptor)))
      (is (= [loan-descriptor release-descriptor base-descriptor
              captured-descriptor]
             (mapv :descriptor @effects)))
      (is (= [:handler :handler :handler :handler]
             (mapv :route @effects)))
      (is (empty? (:ffi-errors @state))))))

(deftest captured-handler-must-return-a-two-element-vector
  (let [descriptor (assoc base-foreign-function-descriptor
                          :capture-native-error? true)
        key (descriptor-handler-key-var descriptor)]
    (doseq [bad [42 [1] [1 2 3] '(1 2) nil]]
      (let [state (atom {:ffi-errors []})
            effects (atom [])
            controller (make-ffi-controller-var
                        {key (fn [_] bad)} state effects)
            data (ex-data-of #(controller descriptor))]
        (is (= :jolt.sim.runtime/invalid-capture-result (:type data))
            (pr-str bad))
        (is (= 1 (count (:ffi-errors @state))) (pr-str bad))
        (is (= :handler-error (:ffi-error (first (:ffi-errors @state))))
            (pr-str bad))))
    ;; A wrong-shape captured return is latched even when application code
    ;; catches the propagated exception.
    (let [state (atom {:ffi-errors []})
          effects (atom [])
          controller (make-ffi-controller-var
                      {key (fn [_] :not-a-vector)} state effects)
          swallowed (try (controller descriptor) :not-thrown
                        (catch :default _ :caught))]
      (is (= :caught swallowed))
      (is (= 1 (count (:ffi-errors @state)))))
    ;; A two-element vector is accepted.
    (let [state (atom {:ffi-errors []})
          effects (atom [])
          controller (make-ffi-controller-var
                      {key (fn [_] [1 2])} state effects)]
      (is (= [1 2] (controller descriptor)))
      (is (empty? (:ffi-errors @state))))))

(deftest original-descriptor-is-preserved-in-effects-and-handler-argument
  (let [descriptor (assoc base-foreign-function-descriptor
                          :arguments [7] :capture-native-error? true)
        key (descriptor-handler-key-var descriptor)
        seen (atom nil)
        state (atom {:ffi-errors []})
        effects (atom [])
        controller (make-ffi-controller-var
                    {key (fn [d] (reset! seen d) [1 2])}
                    state effects)]
    (controller descriptor)
    (is (identical? descriptor @seen))
    (is (= [descriptor] (mapv :descriptor @effects)))
    (is (= [:hermetic] (mapv :mode @effects)))
    (is (= [:handler] (mapv :route @effects)))))

(deftest current-nested-ffi-descriptor-version-is-exact
  (if (rt/available?)
    (do
      (is (= 4 (get-in (rt/capabilities)
                       [:ffi-interception :descriptor-version])))
      (is (= supported-descriptor (rt/capabilities))))
    (ordinary-reports-unavailable rt/capabilities)))

;; ---- Current worker-ownership lifecycle -------------------------------
;;
;; :exit/:abort let cleanup wait for real worker release. These tests exercise
;; the current sim image and clean unavailability on ordinary Jolt.

(deftest completed-future-releases-worker-at-exit
  (if (rt/available?)
    (let [result (rt/run-controlled
                  {}
                  (fn [] (let [worker (future :released)] @worker) :done))]
      (is (= :done (:result result)))
      (is (= supported-descriptor (:capabilities result)))
      (is (= [:spawn :start :finish :exit]
             (mapv :event (:events result))))
      (is (apply = (map :task (:events result)))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest terminal-cancel-is-distinct-from-worker-exit
  ;; A cancelled running worker settles its future with :cancel but remains
  ;; owned until :exit; cleanup waits for that :exit and the run still succeeds.
  (if (rt/available?)
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
    (ordinary-reports-unavailable
     #(rt/run-controlled {:on-event (fn [_])} (fn [] :done)))))

(deftest cleanup-waits-for-every-spawned-worker-to-exit
  ;; After a successful run every spawned task must have released worker
  ;; ownership via exactly one :exit or :abort; none may remain owned.
  (if (rt/available?)
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
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest drain-timeout-is-part-of-the-current-contract
  (if (rt/available?)
    (is (= :ok
           (:result
            (rt/run-controlled
             {:drain-timeout-ms 100}
             (fn [] :ok)))))
    (ordinary-reports-unavailable
     #(rt/run-controlled {:drain-timeout-ms 100} (fn [] :done)))))

(deftest cleanup-drains-a-worker-that-finishes-after-the-body
  (if (rt/available?)
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
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest restoration-waits-for-an-exit-callback-to-return
  (if (rt/available?)
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
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest body-failure-drains-workers-before-restoring
  (if (rt/available?)
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
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest spawn-callback-failure-is-balanced-by-abort
  (if (rt/available?)
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
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest late-nested-spawn-is-rejected-balanced-and-drained
  (if (rt/available?)
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
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

;; ---- Current :future-schedule scripted scheduler ----------------------
;;
;; A :future-schedule is a nonempty vector, an exact permutation of 0..N-1.
;; Ordinals are assigned to accepted parent-zero :spawn events under the
;; explicit single-spawner/quiescent-raw-thread restriction; the schedule
;; declares the order in which those ordinals' bodies are admitted to run, one
;; at a time, releasing the next only after the current ordinal's :finish.
;; These tests exercise the current sim image and clean unavailability on an
;; ordinary image.

(deftest future-schedule-malformed-permutations-fail-before-install
  ;; Shape validation is pure config checking; it reports before ABI
  ;; resolution on every image, including ordinary released Jolt.
  (doseq [bad [[] '(0 1) #{0 1} [1 2] [0 0] [0 2] [-1 0] [0 1.0] [0 "1"] nil]]
    (let [data (ex-data-of
                #(rt/run-controlled
                  {:future-schedule bad}
                  (fn [] :uncontrolled)))]
      (is (= :jolt.sim.runtime/invalid-config (:type data))
          (pr-str bad))
      (is (= bad (:future-schedule data))
          (pr-str bad))))
  (let [resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        resolutions (atom 0)
        data
        (with-redefs-fn
          {resolve-var
           (fn []
             (swap! resolutions inc)
             (throw (ex-info "must not resolve" {})))}
          #(ex-data-of
            (fn []
              (rt/run-controlled
               {:future-schedule [1 2]}
               (fn [] :uncontrolled)))))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (zero? @resolutions)
        "malformed schedule must fail before ABI resolution or installation")))

(deftest future-schedule-is-part-of-the-current-contract
  (if (rt/available?)
    (is (= :ok (:result (rt/run-controlled {:future-schedule [0]}
                                            (fn [] (let [a (future :ok)] @a))))))
    (ordinary-reports-unavailable
     #(rt/run-controlled {:future-schedule [0]} (fn [] :uncontrolled)))))

(deftest future-schedule-drives-exact-body-start-order
  (if (rt/available?)
    (let [observed (atom [])
          result
          (rt/run-controlled
           {:future-schedule [2 0 1]}
           (fn []
             (let [a (future (swap! observed conj :a) :a)
                   b (future (swap! observed conj :b) :b)
                   c (future (swap! observed conj :c) :c)]
               [@a @b @c])))
          schedule-events (:schedule-events result)]
      (is (= [:a :b :c] (:result result)))
      (is (= [:c :a :b] @observed))
      (is (= [[:admit 2] [:complete 2]
              [:admit 0] [:complete 0]
              [:admit 1] [:complete 1]]
             schedule-events)))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-releases-next-at-finish-before-exit
  (if (rt/available?)
    (let [first-task (atom nil)
          first-exit-entered (promise)
          release-first-exit (promise)
          second-started (promise)
          result
          (rt/run-controlled
           {:future-schedule [0 1]
            :on-event
            (fn [{:keys [event task]}]
              (when (= :spawn event)
                (compare-and-set! first-task nil task))
              (when (and (= :exit event) (= task @first-task))
                (deliver first-exit-entered true)
                @release-first-exit))}
           (fn []
             (let [a (future :a)
                   b (future (deliver second-started true) :b)
                   exit-entered
                   (deref first-exit-entered 5000 :timeout)
                   second-before-release
                   (deref second-started 500 :timeout)]
               (deliver release-first-exit true)
               {:values [@a @b]
                :exit-entered exit-entered
                :second-before-release second-before-release})))]
      (is (= [:a :b] (get-in result [:result :values])))
      (is (true? (get-in result [:result :exit-entered])))
      (is (true? (get-in result [:result :second-before-release]))
          "the second body must start while the first exit callback is blocked"))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-admits-at-most-one-body-at-a-time
  (if (rt/available?)
    (let [first-entered (promise)
          second-entered (promise)
          release-first (promise)
          premature-second (atom nil)
          concurrent (atom 0)
          max-concurrent (atom 0)
          run-body
          (fn [tag entered block?]
            (let [n (swap! concurrent inc)]
              (swap! max-concurrent max n))
            (deliver entered true)
            (when block? @release-first)
            (swap! concurrent dec)
            tag)
          result
          (rt/run-controlled
           {:future-schedule [1 0 2]}
           (fn []
             (let [a (future (run-body :a second-entered false))
                   b (future (run-body :b first-entered true))
                   c (future (run-body :c (promise) false))]
               (when (= :timeout (deref first-entered 5000 :timeout))
                 (throw (ex-info "first scheduled body did not start" {})))
               ;; Body B cannot finish until release-first. If body A is
               ;; admitted early, it causally signals second-entered while B
               ;; is still running; no incidental OS serialization can hide
               ;; the overlap.
               (reset! premature-second
                       (deref second-entered 200 :not-entered))
               (deliver release-first true)
               [@a @b @c])))]
      (is (= [:a :b :c] (:result result)))
      (is (= :not-entered @premature-second))
      (is (= 1 @max-concurrent)))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-evidence-is-stable-across-runs-with-changing-raw-ids
  (if (rt/available?)
    (let [runs
          (vec
           (for [_ (range 20)]
             (rt/run-controlled
              {:future-schedule [0 1 2]}
              (fn []
                (let [a (future :a)
                      b (future :b)
                      c (future :c)]
                  [@a @b @c])))))
          first-run (first runs)
          last-run (last runs)
          evidence (mapv :schedule-events runs)]
      (is (every? #(= [:a :b :c] (:result %)) runs))
      (is (< (apply max (map :task (:events first-run)))
             (apply min (map :task (:events last-run)))))
      (is (= [[:admit 0] [:complete 0]
              [:admit 1] [:complete 1]
              [:admit 2] [:complete 2]]
             (first evidence)))
      (is (apply = evidence)
          "the complete returned logical evidence must be replay-stable"))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-rejects-a-spawn-beyond-the-schedule
  (if (rt/available?)
    (let [thrown (ex-of
                  #(rt/run-controlled
                    {:future-schedule [0]}
                    (fn []
                      (let [a (future :a)]
                        @a
                        (future :b)))))
          data (ex-data thrown)]
      (is (= :jolt.sim.runtime/schedule-error (:type data)))
      (is (= :extra-spawn (:reason data)))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-retains-caught-extra-spawn-failure
  (if (rt/available?)
    (let [caught (atom nil)
          data
          (ex-data-of
           #(rt/run-controlled
             {:future-schedule [0]}
             (fn []
               (let [a (future :a)]
                 @a
                 (reset! caught
                         (try
                           (future :extra)
                           :not-rejected
                           (catch :default _ :caught)))
                 :body-returned))))]
      (is (= :caught @caught))
      (is (= :jolt.sim.runtime/schedule-error (:type data)))
      (is (= :extra-spawn (:reason data)))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-rejects-fewer-spawns-than-scheduled
  (if (rt/available?)
    (let [thrown (ex-of
                  #(rt/run-controlled
                    {:future-schedule [0 1 2]}
                    (fn [] (let [a (future :a)] @a))))
          data (ex-data thrown)]
      (is (= :jolt.sim.runtime/schedule-error (:type data)))
      (is (= :missing-spawn (:reason data)))
      (is (= 3 (:expected data)))
      (is (= 1 (:spawned data)))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-rejects-a-nested-spawn-even-when-app-catches-it
  (if (rt/available?)
    (let [parent (atom nil)
          data (ex-data-of
                #(rt/run-controlled
                  {:future-schedule [0]}
                  (fn []
                    (reset! parent
                            (future
                              (try
                                (future :nested)
                                (catch :default _ :child-rejected))))
                    @@parent)))]
      (is (= :jolt.sim.runtime/schedule-error (:type data)))
      (is (= :nested-spawn (:reason data)))
      (is (= :child-rejected @@parent))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-rejects-cancellation
  (if (rt/available?)
    (let [started (promise)
          release (promise)
          data
          (ex-data-of
           #(rt/run-controlled
             {:future-schedule [0]}
             (fn []
               (let [worker
                     (future
                       (deliver started true)
                       @release
                       :unreachable)]
                 (deref started 5000 :timeout)
                 (let [cancelled? (future-cancel worker)]
                   (deliver release true)
                   cancelled?)))))]
      (is (= :jolt.sim.runtime/schedule-error (:type data)))
      (is (= :cancellation-unsupported (:reason data)))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-cancel-before-start-skips-the-gated-body
  (if (rt/available?)
    (let [first-ran? (atom false)
          second-started (promise)
          release-second (promise)
          cancelled? (atom nil)
          data
          (ex-data-of
           #(rt/run-controlled
             {:future-schedule [1 0]}
             (fn []
               (let [first (future (reset! first-ran? true) :first)
                     second
                     (future
                       (deliver second-started true)
                       @release-second
                       :second)]
                 (when (= :timeout (deref second-started 5000 :timeout))
                   (throw (ex-info "selected body did not start" {})))
                 (reset! cancelled? (future-cancel first))
                 (deliver release-second true)
                 @second))))]
      (is (true? @cancelled?))
      (is (false? @first-ran?))
      (is (= :jolt.sim.runtime/schedule-error (:type data)))
      (is (= :cancellation-unsupported (:reason data)))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-user-spawn-callback-failure-aborts-later-gates
  (if (rt/available?)
    (let [spawn-count (atom 0)
          bodies (atom [])
          caught (atom [])
          callback-error (ex-info "spawn observer failed" {:observer :spawn})
          data
          (ex-data-of
           #(rt/run-controlled
             {:future-schedule [1 0 2]
              :on-event
              (fn [{:keys [event]}]
                (when (and (= :spawn event)
                           (= 2 (swap! spawn-count inc)))
                  (throw callback-error)))}
             (fn []
               (future (swap! bodies conj :zero))
               (swap! caught conj
                      (try
                        (future (swap! bodies conj :one))
                        :not-rejected
                        (catch :default _ :caught-second)))
               (swap! caught conj
                      (try
                        (future (swap! bodies conj :two))
                        :not-rejected
                        (catch :default _ :caught-third)))
               :body-returned)))]
      (is (= [:caught-second :caught-third] @caught))
      (is (empty? @bodies))
      (is (= :jolt.sim.runtime/controller-error (:type data)))
      (is (some #(= "spawn observer failed"
                    (get-in % [:error :message]))
                (:errors data)))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-user-finish-callback-failure-does-not-admit-next
  (if (rt/available?)
    (let [bodies (atom [])
          failed? (atom false)
          callback-error (ex-info "finish observer failed" {:observer :finish})
          data
          (ex-data-of
           #(rt/run-controlled
             {:future-schedule [0 1]
              :on-event
              (fn [{:keys [event]}]
                (when (and (= :finish event)
                           (compare-and-set! failed? false true))
                  (throw callback-error)))}
             (fn []
               (let [a (future (swap! bodies conj :a) :a)
                     b (future (swap! bodies conj :b) :b)]
                 @a
                 @b))))]
      (is (= [:a] @bodies))
      (is (= :jolt.sim.runtime/controller-error (:type data)))
      (is (some #(= "finish observer failed"
                    (get-in % [:error :message]))
                (:errors data)))
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-body-failure-still-drains-and-restores
  (if (rt/available?)
    (let [closed (promise)
          worker-started (promise)
          worker (atom nil)
          body-error (ex-info "scheduled body boom" {:phase :run})
          thrown
          (ex-of
           #(with-close-signal
              closed
              (fn []
                (rt/run-controlled
                 {:future-schedule [0] :drain-timeout-ms 2000}
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
      (is (= :recovered (:result (rt/run-controlled {} (fn [] :recovered))))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

(deftest future-schedule-session-recovers-for-a-follow-up-run
  (if (rt/available?)
    (let [failed (ex-data-of
                  #(rt/run-controlled
                    {:future-schedule [0]}
                    (fn []
                      (let [a (future :a)]
                        @a
                        (future :b)))))
          scheduled-again (rt/run-controlled
                            {:future-schedule [0]}
                            (fn [] (let [a (future :a)] @a)))
          unscheduled (rt/run-controlled {} (fn [] :done))]
      (is (= :jolt.sim.runtime/schedule-error (:type failed)))
      (is (= :a (:result scheduled-again)))
      (is (= :done (:result unscheduled))))
    (ordinary-reports-unavailable #(rt/run-controlled {} (fn [] :done)))))

;; ---- Current proceed-routing policy ------------------------------------
;;
;; Pure mock tests keep the mode contract covered on an ordinary released
;; image. Live branches below additionally exercise the current Scheme
;; routing continuation when the canonical sim image runs this suite.

(defn- mock-resolved-abi-vars
  "Returns a replacement value for jolt.sim.runtime/resolved-abi-vars whose
  :capabilities yields descriptor and whose other slots are derefable stubs.
  When omit-routing? is true the :install-ffi-routing-controller! slot is nil,
  simulating a current image that lacks the routing installer var."
  [descriptor omit-routing?]
  (let [stub (atom (fn [& _] nil))]
    (cond-> {:capabilities (atom (fn [] descriptor))
             :install-controller! stub
             :restore-controller! stub
             :controller-errors stub
             :clear-controller-errors! stub
             :install-ffi-controller! stub
             :restore-ffi-controller! stub}
      (not omit-routing?) (assoc :install-ffi-routing-controller! stub))))

(defn- mock-controller-ops [descriptor established routing]
  {:descriptor descriptor
   :clear-controller-errors! (fn [])
   :install-controller! (fn [_] :future-token)
   :install-ffi-controller!
   (fn [controller]
     (when established (reset! established controller))
     :ffi-token)
   :install-ffi-routing-controller!
   (fn [controller]
     (when routing (reset! routing controller))
     :routing-token)
   :restore-controller! (fn [_])
   :restore-ffi-controller! (fn [_])
   :controller-errors (fn [])})

(defn- native-descriptor [operation arguments]
  {:kind :native-operation
   :task 0
   :arguments arguments
   :operation operation})

(deftest current-contract-requires-the-ffi-routing-installer-var
  (let [resolved-var (resolve 'jolt.sim.runtime/resolved-abi-vars)]
    ;; A current image that advertises the exact descriptor but omits the routing
    ;; installer var is incompatible, even though run-controlled never invokes
    ;; that installer. It shares restore-ffi-controller! for restoration.
    (let [data
          (with-redefs-fn
            {resolved-var (fn [] (mock-resolved-abi-vars supported-descriptor true))}
            #(ex-data-of rt/capabilities))]
      (is (= :jolt.sim.runtime/abi-incompatible (:type data)))
      (is (= [:install-ffi-routing-controller!] (:missing data))))
    (let [data
          (with-redefs-fn
            {resolved-var (fn [] (mock-resolved-abi-vars supported-descriptor true))}
            #(ex-data-of rt/available?))]
      (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
    ;; A complete image resolves the one exact current descriptor.
    (let [caps
          (with-redefs-fn
            {resolved-var (fn [] (mock-resolved-abi-vars supported-descriptor false))}
            #(rt/capabilities))]
      (is (= supported-descriptor caps)))))

(deftest runtime-resolution-distinguishes-absence-from-incompatible-versions
  (let [resolved-var (resolve 'jolt.sim.runtime/resolved-abi-vars)
        absent {:capabilities nil
                :install-controller! nil
                :restore-controller! nil
                :controller-errors nil
                :clear-controller-errors! nil
                :install-ffi-controller! nil
                :restore-ffi-controller! nil
                :install-ffi-routing-controller! nil}]
    (with-redefs-fn
      {resolved-var (fn [] absent)}
      #(do
         (is (false? (rt/available?)))
         (is (= :jolt.sim.runtime/abi-unavailable
                (:type (ex-data-of rt/capabilities))))))
    (doseq [version [3 4 6 7]]
      (let [descriptor (assoc supported-descriptor :abi-version version)
            data
            (with-redefs-fn
              {resolved-var
               (fn [] (mock-resolved-abi-vars descriptor false))}
              #(ex-data-of rt/available?))]
        (is (= :jolt.sim.runtime/abi-incompatible (:type data))
            (pr-str version))))))

(deftest hermetic-run-retains-the-established-ffi-controller
  ;; Hermetic mode installs the established
  ;; one-argument FFI controller (install-ffi-controller!) and never the routing
  ;; installer (install-ffi-routing-controller!), which is required to exist on
  ;; the image but unused by the controlled run. A thunk that spawns nothing
  ;; drains immediately and restores.
  (let [established-ffi (atom nil)
        routing (atom nil)
        future-installed (atom nil)
        ops {:descriptor supported-descriptor
             :clear-controller-errors! (fn [])
             :install-controller!
             (fn [controller]
               (reset! future-installed controller)
               :future-token)
             :install-ffi-controller!
             (fn [controller]
               (reset! established-ffi controller)
               :ffi-token)
             :install-ffi-routing-controller!
             (fn [controller]
               (reset! routing controller)
               :routing-token)
             :restore-controller! (fn [_])
             :restore-ffi-controller! (fn [_])
             :controller-errors (fn [])}
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn [] ops)}
      #(let [result (rt/run-controlled {} (fn [] :done))]
         (is (= :done (:result result)))
         (is (= supported-descriptor (:capabilities result)))
         (is (some? @established-ffi)
             "the established one-argument FFI controller must be installed")
         (is (nil? @routing)
             "the routing installer must not be invoked by run-controlled")
         (is (some? @future-installed)
             "the future lifecycle controller must be installed")))))

(deftest observe-installs-routing-proceeds-once-and-records-the-route
  (let [established (atom nil)
        routing (atom nil)
        proceed-count (atom 0)
        descriptor (native-descriptor :sizeof [:int])
        ops (mock-controller-ops supported-descriptor established routing)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        run
        (with-redefs-fn
          {resolve-var (fn [] ops)}
          #(rt/run-controlled
            {:ffi-mode :observe}
            (fn []
              ((deref routing)
               descriptor
               (fn [] (swap! proceed-count inc) 8)))))]
    (is (= 8 (:result run)))
    (is (= 1 @proceed-count))
    (is (nil? @established))
    (is (some? @routing))
    (is (= [descriptor] (:effects run)))
    (is (= [{:mode :observe :route :native :descriptor descriptor}]
           (:effect-trace run)))))

(deftest observe-preserves-a-caught-native-proceed-exception
  (let [routing (atom nil)
        descriptor (native-descriptor :sizeof [:int])
        ops (mock-controller-ops supported-descriptor nil routing)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        run
        (with-redefs-fn
          {resolve-var (fn [] ops)}
          #(rt/run-controlled
            {:ffi-mode :observe}
            (fn []
              (try
                ((deref routing)
                 descriptor
                 (fn []
                   (throw (ex-info "native failure" {:source :native}))))
                :not-thrown
                (catch :default error
                  (:source (ex-data error)))))))]
    (is (= :native (:result run)))
    (is (= [:native] (mapv :route (:effect-trace run))))))

(deftest hybrid-routes-handlers-and-safe-misses-explicitly
  (let [routing (atom nil)
        proceeded (atom [])
        alloc (native-descriptor :alloc [8])
        sizeof (native-descriptor :sizeof [:int])
        loaded (native-descriptor :loaded? ["libc"])
        ops (mock-controller-ops supported-descriptor nil routing)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        run
        (with-redefs-fn
          {resolve-var (fn [] ops)}
          #(rt/run-controlled
            {:ffi-mode :hybrid
             :ffi-handlers
             {[:native-operation :alloc]
              (fn [_] (rt/modeled-resource 1042000000 8))
              [:native-operation :sizeof]
              (fn [_] (rt/substitute 16))}}
            (fn []
              [((deref routing) alloc
                (fn [] (swap! proceeded conj :alloc) :wrong))
               ((deref routing) sizeof
                (fn [] (swap! proceeded conj :sizeof) :wrong))
               ((deref routing) loaded
                (fn [] (swap! proceeded conj :loaded) true))])))]
    (is (= [1042000000 16 true] (:result run)))
    (is (= [:loaded] @proceeded))
    (is (= [:handler :handler :native]
           (mapv :route (:effect-trace run))))
    (is (= (:effects run) (mapv :descriptor (:effect-trace run))))))

(deftest hybrid-blocks-a-derived-modeled-resource-before-proceed
  (let [routing (atom nil)
        proceed-count (atom 0)
        alloc (native-descriptor :alloc [8])
        free-derived (native-descriptor :free [1042000007])
        ops (mock-controller-ops supported-descriptor nil routing)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        data
        (with-redefs-fn
          {resolve-var (fn [] ops)}
          #(ex-data-of
            (fn []
              (rt/run-controlled
               {:ffi-mode :hybrid
                :ffi-handlers
                {[:native-operation :alloc]
                 (fn [_] (rt/modeled-resource 1042000000))}}
               (fn []
                 ((deref routing) alloc
                  (fn [] (swap! proceed-count inc) :wrong))
                 (try
                   ((deref routing) free-derived
                    (fn [] (swap! proceed-count inc) nil))
                   (catch :default _ :caught))
                 :apparently-ok)))))]
    (is (= :jolt.sim.runtime/controller-error (:type data)))
    (is (zero? @proceed-count))
    (is (= [:alloc :free] (mapv :operation (:effects data))))
    (is (= [:handler :blocked] (mapv :route (:effect-trace data))))
    (is (= :modeled-resource-native-fallback
           (:reason (second (:effect-trace data)))))
    (is (some #(= :modeled-resource-native-fallback (:ffi-error %))
              (:errors data)))
    ;; A policy failure is latched for this run but exact restoration leaves the
    ;; adapter reusable; it does not poison controller ownership.
    (let [follow-up
          (with-redefs-fn
            {resolve-var (fn [] ops)}
            #(rt/run-controlled {} (fn [] :recovered)))]
      (is (= :recovered (:result follow-up))))))

(deftest hybrid-function-results-must-be-classified
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        descriptor (native-descriptor :alloc [4])
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {[:native-operation :alloc] (fn [_] 1042)}
         state effect-trace ledger)
        data (ex-data-of #(controller descriptor (fn [] :native)))]
    (is (= :jolt.sim.runtime/invalid-handler-result (:type data)))
    (is (= :unclassified-result (:reason data)))
    (is (= [:handler] (mapv :route @effect-trace)))
    (is (= :invalid-handler-result
           (:ffi-error (first (:ffi-errors @state)))))))

(deftest hybrid-substitute-cannot-bypass-known-pointer-provenance
  (let [foreign-pointer
        {:kind :foreign-function :task 0 :arguments []
         :symbol "modeled_pointer" :argument-types [] :return-type :pointer
         :blocking? false :capture-native-error? false}
        foreign-void-pointer (assoc foreign-pointer :return-type :void*)
        alloc (native-descriptor :alloc [8])
        borrow (native-descriptor :borrow-byte-array
                                  [(byte-array [1]) 0 1])
        string-pointer (native-descriptor :string->ptr ["x"])
        pointer-descriptors
        [alloc
         (native-descriptor :read [5000 :pointer 0])
         (native-descriptor :read [5000 :void* 0])
         foreign-pointer
         foreign-void-pointer]
        fixed-native-descriptors [alloc borrow string-pointer]]
    (letfn
     [(assert-rejected [descriptor fake]
        (let [state (atom {:ffi-errors []})
              effect-trace (atom [])
              proceeded? (atom false)
              key (descriptor-handler-key-var descriptor)
              controller
              (make-ffi-routing-controller-var
               :hybrid
               {key (fn [_] (rt/substitute fake))}
               state effect-trace (atom []))
              data
              (ex-data-of
               #(controller descriptor
                            (fn [] (reset! proceeded? true) :native)))]
          (is (= :jolt.sim.runtime/invalid-handler-result (:type data)))
          (is (= (if (= :borrow-byte-array (:operation descriptor))
                   :borrow-requires-positive-modeled-resource
                   :resource-requires-modeled-resource)
                 (:reason data)))
          (is (false? @proceeded?))
          (is (= [:handler] (mapv :route @effect-trace)))
          (is (= :invalid-handler-result
                 (:ffi-error (first (:ffi-errors @state)))))))]
      (doseq [descriptor pointer-descriptors
              fake [1042000000 1042000000.0]]
        (assert-rejected descriptor fake))
      ;; These native operations cannot legitimately return a negative pointer;
      ;; jnum->exact would otherwise turn it into a real address on a later miss.
      (doseq [descriptor fixed-native-descriptors
              fake [-1042000000 -1042000000.0]]
        (assert-rejected descriptor fake))))
  ;; Explicit null/failure is not an owned resource and remains a valid
  ;; substitution for a pointer-producing operation.
  (let [state (atom {:ffi-errors []})
        descriptor (native-descriptor :alloc [8])
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {[:native-operation :alloc] (fn [_] (rt/substitute 0))}
         state (atom []) (atom []))]
    (is (zero? (controller descriptor (fn [] :wrong))))
    (is (empty? (:ffi-errors @state))))
  ;; Pointer-typed foreign calls may use a negative API failure sentinel.
  (let [state (atom {:ffi-errors []})
        descriptor
        {:kind :foreign-function :task 0 :arguments []
         :symbol "sentinel_pointer" :argument-types [] :return-type :pointer
         :blocking? false :capture-native-error? false}
        key (descriptor-handler-key-var descriptor)
        controller
        (make-ffi-routing-controller-var
         :hybrid {key (fn [_] (rt/substitute -1))}
         state (atom []) (atom []))]
    (is (= -1 (controller descriptor (fn [] :wrong))))
    (is (empty? (:ffi-errors @state)))))

(def ^:private invalid-borrow-handler-cases
  [[:nil-handler nil]
   [:substitute-zero (fn [_] (rt/substitute 0))]
   [:modeled-zero (fn [_] (rt/modeled-resource 0))]])

(deftest hybrid-borrow-requires-a-positive-modeled-resource-directly
  (let [descriptor (native-descriptor :borrow-byte-array
                                      [(byte-array [1]) 0 1])]
    (doseq [[label handler] invalid-borrow-handler-cases]
      (let [state (atom {:ffi-errors []})
            effect-trace (atom [])
            proceeded? (atom false)
            controller
            (make-ffi-routing-controller-var
             :hybrid
             {[:native-operation :borrow-byte-array] handler}
             state effect-trace (atom []))
            data
            (ex-data-of
             #(controller descriptor
                          (fn [] (reset! proceeded? true) :native)))]
        (is (= :jolt.sim.runtime/invalid-handler-result (:type data))
            (str label))
        (is (= :borrow-requires-positive-modeled-resource (:reason data))
            (str label))
        (is (false? @proceeded?) (str label))
        (is (= [:handler] (mapv :route @effect-trace)) (str label))
        (is (= :invalid-handler-result
               (:ffi-error (first (:ffi-errors @state))))
            (str label))))))

(deftest hybrid-caught-invalid-borrow-remains-a-controller-error
  (let [resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        descriptor (native-descriptor :borrow-byte-array
                                      [(byte-array [1]) 0 1])]
    (doseq [[label handler] invalid-borrow-handler-cases]
      (let [routing (atom nil)
            caught (atom nil)
            ops (mock-controller-ops supported-descriptor nil routing)
            data
            (with-redefs-fn
              {resolve-var (fn [] ops)}
              #(ex-data-of
                (fn []
                  (rt/run-controlled
                   {:ffi-mode :hybrid
                    :ffi-handlers
                    {[:native-operation :borrow-byte-array] handler}}
                   (fn []
                     (reset! caught
                             (try
                               ((deref routing) descriptor (fn [] :native))
                               :not-thrown
                               (catch :default error
                                 (:type (ex-data error)))))
                     :apparently-ok)))))]
        (is (= :jolt.sim.runtime/invalid-handler-result @caught) (str label))
        (is (= :jolt.sim.runtime/controller-error (:type data)) (str label))
        (is (= [:handler] (mapv :route (:effect-trace data))) (str label))
        (is (some #(= :invalid-handler-result (:ffi-error %))
                  (:errors data))
            (str label))))))

(deftest hybrid-blocks-inexact-aliases-of-modeled-resources-directly
  ;; Chez truncates every numeric native argument through jnum->exact. Match
  ;; that conversion before provenance lookup, including the zero-base edge
  ;; where a negative fraction truncates to zero.
  (doseq [[base alias expected-native]
          [[1042000000 1042000000.75 1042000000]
           [0 -0.5 0]
           ;; Above DOUBLE's exact-integer range, a ratio must not round away
           ;; from the fake base before the provenance interval comparison.
           [9007199254740993 (+ 9007199254740993 1/2)
            9007199254740993]]]
    (let [state (atom {:ffi-errors []})
          effect-trace (atom [])
          proceeded? (atom false)
          controller
          (make-ffi-routing-controller-var
           :hybrid
           {[:native-operation :alloc]
            (fn [_] (rt/modeled-resource base 8))}
           state effect-trace (atom []))]
      (is (= base
             (controller (native-descriptor :alloc [8])
                         (fn [] :wrong))))
      (let [data
            (ex-data-of
             #(controller (native-descriptor :free [alias])
                          (fn [] (reset! proceeded? true) nil)))]
        (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data)))
        (is (false? @proceeded?))
        (is (= alias (:argument data)))
        (is (= expected-native (:native-argument data)))
        (is (= [:handler :blocked] (mapv :route @effect-trace)))
        (is (= :modeled-resource-native-fallback
               (:ffi-error (first (:ffi-errors @state)))))))))

(deftest hybrid-caught-inexact-resource-alias-remains-a-controller-error
  (let [routing (atom nil)
        fake-base 9007199254740993
        alias (+ fake-base 1/2)
        alloc (native-descriptor :alloc [8])
        free-alias (native-descriptor :free [alias])
        ops (mock-controller-ops supported-descriptor nil routing)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        caught (atom nil)
        proceeded? (atom false)
        data
        (with-redefs-fn
          {resolve-var (fn [] ops)}
          #(ex-data-of
            (fn []
              (rt/run-controlled
               {:ffi-mode :hybrid
                :ffi-handlers
                {[:native-operation :alloc]
                 (fn [_] (rt/modeled-resource fake-base 8))}}
               (fn []
                 ((deref routing) alloc (fn [] :wrong))
                 (reset! caught
                         (try
                           ((deref routing) free-alias
                            (fn [] (reset! proceeded? true) nil))
                           :not-thrown
                           (catch :default error
                             (:type (ex-data error)))))
                 :apparently-ok)))))]
    (is (= :jolt.sim.runtime/modeled-resource-native-fallback @caught))
    (is (= :jolt.sim.runtime/controller-error (:type data)))
    (is (false? @proceeded?))
    (is (= [:handler :blocked] (mapv :route (:effect-trace data))))
    (is (= alias (get-in data [:errors 0 :error :data :argument])))
    (is (= fake-base
           (get-in data [:errors 0 :error :data :native-argument])))))

(deftest captured-modeled-resource-tracks-the-primary-result-span
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        pointer-descriptor
        {:kind :foreign-function :task 0 :arguments []
         :symbol "modeled_pointer" :argument-types [] :return-type :pointer
         :blocking? false :capture-native-error? true}
        key (descriptor-handler-key-var pointer-descriptor)
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {key (fn [_] (rt/modeled-resource [7000 5] 4))}
         state effect-trace ledger)]
    (is (= [7000 5]
           (controller pointer-descriptor (fn [] :wrong))))
    (let [free (native-descriptor :free [7003])
          proceeded? (atom false)
          data (ex-data-of
                #(controller free
                             (fn [] (reset! proceeded? true) nil)))]
      (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data)))
      (is (false? @proceeded?))
      (is (= [:handler :blocked] (mapv :route @effect-trace))))))

(deftest live-observe-proceeds-real-ffi-and-does-not-latch-native-errors
  (if (rt/available?)
    (let [expected-size (ffi/sizeof :int)
          run
          (rt/run-controlled
           {:ffi-mode :observe}
           (fn []
             [(ffi/sizeof :int)
              (try (sim-ghost 9)
                   :not-thrown
                   (catch :default _ :caught))]))]
      (is (= [expected-size :caught] (:result run)))
      (is (= [:native :native] (mapv :route (:effect-trace run))))
      (is (= [:sizeof :foreign-function]
             (mapv #(if (= :native-operation (:kind %))
                      (:operation %)
                      (:kind %))
                   (:effects run)))))

    (ordinary-reports-unavailable
     #(rt/run-controlled {:ffi-mode :observe} (fn [] :done)))))

(deftest live-hybrid-mixes-modeled-and-real-effects-and-blocks-provenance
  (if (rt/available?)
    (let [fake-base 1042000000
          expected-size (ffi/sizeof :int)
          mixed
          (rt/run-controlled
           {:ffi-mode :hybrid
            :ffi-handlers
            {[:native-operation :alloc]
             (fn [_] (rt/modeled-resource fake-base))}}
           (fn [] [(ffi/alloc 8) (ffi/sizeof :int)]))]
      (is (= [fake-base expected-size] (:result mixed)))
      (is (= [:handler :native] (mapv :route (:effect-trace mixed))))
      (let [data
            (ex-data-of
             #(rt/run-controlled
               {:ffi-mode :hybrid
                :ffi-handlers
                {[:native-operation :alloc]
                 (fn [_] (rt/modeled-resource fake-base))}}
               (fn []
                 (let [pointer (ffi/alloc 8)]
                   (try (ffi/free (+ pointer 7))
                        (catch :default _ :caught)))
                 :apparently-ok)))]
        (is (= :jolt.sim.runtime/controller-error (:type data)))
        (is (= [:handler :blocked] (mapv :route (:effect-trace data))))
        (is (some #(= :modeled-resource-native-fallback (:ffi-error %))
                  (:errors data)))))

    (ordinary-reports-unavailable
     #(rt/run-controlled {:ffi-mode :hybrid} (fn [] :done)))))
