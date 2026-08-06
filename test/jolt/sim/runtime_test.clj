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
  {:abi-version 6
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel :exit :abort]
   :installation
   {:configuration-keys [:future :ffi :clock]
    :install-arity 1 :restore-arity 1
    :atomic? true :strict-lifo? true
    :future-controller-arity 3
    :ffi-controller-arity 2
    :clock-controller-arity 2}
   :ffi-interception {:descriptor-version 8
                      :kinds [:foreign-function :native-operation]
                      :arguments :live
                      :task-identity :future-lifecycle
                      :native-operations [:load-library :loaded? :alloc :free
                                           :read :write :sizeof :null?
                                           :read-bytes
                                           :write-bytes :read-array :read-array!
                                           :write-array :ptr->string
                                           :string->ptr]
                      :proceed-routing {:controller-arity 2
                                        :proceed-arity 0
                                        :single-use true
                                        :dynamic-extent true
                                        :owner-thread true
                                        :lifo true
                                        :scoped-byte-array-release :runtime-owned}
                      :scoped-byte-array-view
                      {:operations [:read-active-byte-array-view
                                    :write-active-byte-array-view!]
                       :read-arity 2
                       :write-arity 2
                       :owner-thread true
                       :dynamic-extent true
                       :runtime-owned true}}
   :clock-interception
   {:descriptor-version 1
    :operations [:mono-nanos]
    :result :exact-integer-nanoseconds
    :nondecreasing? true
    :supervisor-operation :supervisor-mono-nanos
    :proceed-routing {:controller-arity 2
                      :proceed-arity 0
                      :single-use true
                      :dynamic-extent true
                      :owner-thread true
                      :lifo true}}})

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

;; A defsim scenario declared with the optional [input] binding form.
(rt/defsim input-scenario [input] {} {:echoed input})

(rt/defsim input-config-scenario [input]
  {:drain-timeout-ms (:drain-timeout-ms input)}
  (:value input))

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
  (is (false? (:jolt.sim/accepts-input (meta #'sample-scenario))))
  (is (true? (:jolt.sim/activity-lifecycle-owned
              (meta #'sample-scenario))))
  (if (rt/available?)
    (let [result (sample-scenario)]
      (is (= :scenario-result (:result result)))
      (is (= 6 (:abi-version (:capabilities result)))))
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

(deftest defsim-old-form-scenarios-reject-non-nil-input
  (if (rt/available?)
    (let [data (ex-data-of #(sample-scenario {} :not-nil))]
      (is (= :jolt.sim.runtime/scenario-rejects-input (:type data)))
      (is (= 'sample-scenario (:scenario data)))
      (is (= :not-nil (:input data))))
    (ordinary-reports-unavailable #(sample-scenario {} nil))))

(deftest defsim-old-form-scenarios-accept-nil-input-at-every-arity
  (if (rt/available?)
    (do
      (is (= :scenario-result (:result (sample-scenario))))
      (is (= :scenario-result (:result (sample-scenario {}))))
      (is (= :scenario-result (:result (sample-scenario {} nil)))))
    (ordinary-reports-unavailable sample-scenario)))

(deftest defsim-input-binding-form-binds-the-supplied-input
  (is (true? (:jolt.sim/scenario (meta #'input-scenario))))
  (is (true? (:jolt.sim/accepts-input (meta #'input-scenario))))
  (is (true? (:jolt.sim/activity-lifecycle-owned
              (meta #'input-scenario))))
  (if (rt/available?)
    (do
      ;; The zero- and one-argument arities always pass nil input.
      (is (= {:echoed nil} (:result (input-scenario))))
      (is (= {:echoed nil} (:result (input-scenario {}))))
      (is (= {:echoed [:answer 42]} (:result (input-scenario {} [:answer 42])))))
    (ordinary-reports-unavailable input-scenario)))

(deftest defsim-input-binding-is-visible-to-declared-config
  (if (rt/available?)
    (is (= :configured-from-input
           (:result
            (input-config-scenario
             {}
             {:drain-timeout-ms 1000 :value :configured-from-input}))))
    (ordinary-reports-unavailable
     #(input-config-scenario
       {}
       {:drain-timeout-ms 1000 :value :configured-from-input}))))

(defn- macroexpansion-error-data
  "Returns the ex-data of a failed eval, unwrapping one compiler-wrapper cause
  if the top-level exception itself carries none."
  [form]
  (try
    (eval form)
    nil
    (catch :default error
      (or (ex-data error)
          (some-> (ex-cause error) ex-data)))))

(deftest defsim-input-binding-vector-must-hold-exactly-one-symbol
  (let [data
        (macroexpansion-error-data
         '(jolt.sim.runtime/defsim bad-arity-scenario [a b] {} nil))]
    (is (= :jolt.sim.runtime/invalid-defsim (:type data)))
    (is (= :binding-arity (:reason data)))
    (is (= 'bad-arity-scenario (:scenario data))))
  (let [data
        (macroexpansion-error-data
         '(jolt.sim.runtime/defsim bad-symbol-scenario [42] {} nil))]
    (is (= :jolt.sim.runtime/invalid-defsim (:type data)))
    (is (= :binding-not-a-simple-symbol (:reason data))))
  (let [data
        (macroexpansion-error-data
         '(jolt.sim.runtime/defsim bad-qualified-scenario [foo/input] {} nil))]
    (is (= :jolt.sim.runtime/invalid-defsim (:type data)))
    (is (= :binding-not-a-simple-symbol (:reason data)))))

(deftest defsim-declaration-requires-a-config-form
  (let [old-form
        (macroexpansion-error-data
         '(jolt.sim.runtime/defsim missing-old-config))
        input-form
        (macroexpansion-error-data
         '(jolt.sim.runtime/defsim missing-input-config [input]))]
    (is (= :jolt.sim.runtime/invalid-defsim (:type old-form)))
    (is (= :missing-config (:reason old-form)))
    (is (= 'missing-old-config (:scenario old-form)))
    (is (= :jolt.sim.runtime/invalid-defsim (:type input-form)))
    (is (= :missing-config (:reason input-form)))
    (is (= 'missing-input-config (:scenario input-form)))
    (is (= '[input] (:binding input-form)))))

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

;; The unified FFI callback is arity 2. Hermetic routing ignores proceed and
;; must never invoke it, so this sentinel throws if a hermetic controller
;; accidentally calls its proceed continuation.
(def ^:private ignored-proceed
  (fn [] (throw (ex-info "hermetic routing must not invoke proceed"
                         {:type :test/proceed-invoked}))))

(def ^:private make-ffi-routing-controller-var
  (resolve 'jolt.sim.runtime/make-ffi-routing-controller))

(def ^:private register-modeled-resource-var
  (resolve 'jolt.sim.runtime/register-modeled-resource!))

(deftest exact-current-capability-descriptor-is-accepted-and-mismatches-rejected
  ;; Pure structural validation, independent of the running image.
  (is (= supported-descriptor
         (validate-descriptor-var supported-descriptor)))
  (is (= supported-descriptor
         @(resolve 'jolt.sim.runtime/supported-descriptor)))
  ;; Prerelease versions are exact: stale and future ABI numbers are rejected
  ;; rather than accumulating compatibility branches.
  (doseq [version [3 4 5 7 8]]
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
                  (vec (remove #{:null?}
                               (get-in supported-descriptor
                                       [:ffi-interception :native-operations]))))
        data (ex-data-of #(validate-descriptor-var short))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [reordered
        (assoc-in supported-descriptor [:ffi-interception :native-operations]
                  [:load-library :loaded? :alloc :free
                    :read :write :sizeof :read-bytes
                    :write-bytes :read-array :read-array! :write-array
                    :null? :ptr->string :string->ptr])
        data (ex-data-of #(validate-descriptor-var reordered))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (dissoc supported-descriptor :controller-errors)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  ;; The composite installation contract is exact: a wrong callback arity or a
  ;; missing callback slot is rejected.
  (let [bad (assoc-in supported-descriptor
                      [:installation :ffi-controller-arity] 1)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (update-in supported-descriptor
                       [:installation :configuration-keys] pop)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  ;; The clock descriptor is exact: a wrong version, operation, supervisor
  ;; operation, or nondecreasing flag is rejected, validating before user code.
  (let [bad (assoc-in supported-descriptor
                      [:clock-interception :descriptor-version] 2)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (assoc-in supported-descriptor
                      [:clock-interception :operations] [:mono-nanos :extra])
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (assoc-in supported-descriptor
                      [:clock-interception :supervisor-operation]
                      :other-mono-nanos)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (assoc-in supported-descriptor
                      [:clock-interception :nondecreasing?] false)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data))))
  (let [bad (dissoc supported-descriptor :clock-interception)
        data (ex-data-of #(validate-descriptor-var bad))]
    (is (= :jolt.sim.runtime/abi-incompatible (:type data)))))

(def ^:private base-foreign-function-descriptor
  {:kind :foreign-function :task 0 :arguments [0]
   :symbol "s" :argument-types [:int] :return-type :int :blocking? false
   :varargs-after nil})

(def ^:private native-operation-valid-arguments
  {:load-library []
   :loaded? [nil]
   :alloc [nil]
   :free [nil]
   :read [nil nil]
   :write [nil nil nil nil]
   :sizeof [nil]
   :null? [nil]
   :read-bytes [nil nil]
   :write-bytes [nil nil]
   :read-array [nil nil]
   :read-array! [nil nil nil nil]
   :write-array [nil nil]
   :ptr->string [nil]
   :string->ptr [nil]})

(deftest ffi-descriptor-shape-is-exact-for-the-current-contract
  (let [descriptor (assoc base-foreign-function-descriptor
                          :capture-native-error? true)]
    (is (= descriptor (validate-ffi-descriptor-var descriptor))))
  (let [data (ex-data-of
              #(validate-ffi-descriptor-var base-foreign-function-descriptor))]
    (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data)))
    (is (= :foreign-function-key-mismatch (:reason data))))
  (let [bad (-> base-foreign-function-descriptor
                (assoc :capture-native-error? false)
                (dissoc :varargs-after))
        data (ex-data-of #(validate-ffi-descriptor-var bad))]
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
    (let [op-descriptor {:kind :native-operation :task 0
                         :arguments (get native-operation-valid-arguments op)
                         :operation op}]
      (is (= op-descriptor (validate-ffi-descriptor-var op-descriptor))
          (pr-str op))))
  (doseq [op (keys native-operation-valid-arguments)]
    (let [bad {:kind :native-operation :task 0
               :arguments [nil nil nil nil nil]
               :operation op}
          data (ex-data-of #(validate-ffi-descriptor-var bad))]
      (is (= :invalid-native-operation-arity (:reason data)) (pr-str op))))
  (let [bad {:kind :native-operation :task 0 :arguments []
             :operation :not-current}
        data (ex-data-of #(validate-ffi-descriptor-var bad))]
    (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data)))
    (is (= :unknown-operation (:reason data)))))

(deftest ffi-descriptor-varargs-boundary-is-exact
  (let [descriptor (assoc base-foreign-function-descriptor
                          :capture-native-error? false
                          :argument-types [:int :double]
                          :arguments [1 2.0])]
    (doseq [boundary [nil 1 2]]
      (let [candidate (assoc descriptor :varargs-after boundary)]
        (is (= candidate (validate-ffi-descriptor-var candidate))
            (pr-str boundary))))
    (doseq [boundary [0 -1 3 1.0 1/2 "1" true :one
                      :jolt.sim.test/one [:int]]]
      (let [candidate (assoc descriptor :varargs-after boundary)
            data (ex-data-of #(validate-ffi-descriptor-var candidate))]
        (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data))
            (pr-str boundary))
        (is (= :invalid-varargs-after (:reason data))
            (pr-str boundary))))))

(deftest ffi-handler-keys-accept-shorthands-and-canonical-seven-element-forms
  (let [five-key [:foreign-function "s" [:int] :int false]
        handler (fn [_] :ok)
        canonical (validate-ffi-handlers-var {five-key handler})]
    (is (= {[:foreign-function "s" [:int] :int false false nil] handler}
           canonical)))
  (let [six-key [:foreign-function "s" [:int] :int false true]
        handler (fn [_] :ok)]
    (is (= {[:foreign-function "s" [:int] :int false true nil] handler}
           (validate-ffi-handlers-var {six-key handler}))))
  ;; The canonical seven-element key with a nil boundary passes through.
  (let [seven-key [:foreign-function "s" [:int] :int false true nil]
        handler (fn [_] :ok)]
    (is (= {seven-key handler}
           (validate-ffi-handlers-var {seven-key handler}))))
  ;; A canonical seven-element key with a positive in-range boundary is
  ;; distinct from its nil-boundary equivalent and is accepted as written.
  (let [variadic-key [:foreign-function "s" [:int :int] :int false true 1]
        handler (fn [_] :ok)]
    (is (= {variadic-key handler}
           (validate-ffi-handlers-var {variadic-key handler})))))

(deftest ffi-handler-keys-recognize-current-native-operations
  ;; Handler config validation is pure config checking that runs before ABI
  ;; resolution, so it recognizes the current null? operation as a supported
  ;; handler key; a still-unknown operation fails closed.
  (let [handler (fn [_] true)]
    (is (= {[:native-operation :null?] handler}
           (validate-ffi-handlers-var
            {[:native-operation :null?] handler}))))
  ;; The descriptor-8 contract keeps the scoped byte-array loan lifecycle
  ;; runtime-owned and therefore omits its acquire/release operations
  ;; from the controller boundary; their former keys now fail closed as
  ;; unknown operations.
  (doseq [removed [:borrow-byte-array :release-byte-array]]
    (let [data (ex-data-of
                #(validate-ffi-handlers-var
                  {[:native-operation removed] (fn [_])}))]
      (is (= :jolt.sim.runtime/invalid-config (:type data))
          (pr-str removed))))
  (let [unknown [:native-operation :still-not-an-operation]
        data (ex-data-of
              #(validate-ffi-handlers-var {unknown (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= unknown (:handler-key data)))))

(deftest malformed-handler-keys-are-rejected
  (let [bad-capture [:foreign-function "s" [:int] :int false "yes"]
        data (ex-data-of
              #(validate-ffi-handlers-var {bad-capture (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= bad-capture (:handler-key data))))
  ;; An eight-element key exceeds the canonical form even with descriptor 8.
  (let [too-long [:foreign-function "s" [:int] :int false true nil :extra]
        data (ex-data-of
              #(validate-ffi-handlers-var {too-long (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data))))
  (let [namespaced-return
        [:foreign-function "s" [:int] :jolt.sim.test/int false true]
        data (ex-data-of
              #(validate-ffi-handlers-var {namespaced-return (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= namespaced-return (:handler-key data)))))

(deftest malformed-varargs-boundary-in-seven-element-keys-is-rejected
  ;; The boundary must be nil or a positive integer no greater than the
  ;; argument-type count. Every other value fails closed.
  (doseq [bad-boundary [0 -1 3                  ; zero, negative, out-of-range
                        1.0 1/2                 ; non-integer numerics
                        "1" true :one           ; non-numeric scalars
                        :jolt.sim.test/int      ; namespaced keyword
                        [:int]]]                ; aggregate shape
    (let [bad-key [:foreign-function "s" [:int :int] :int false true
                   bad-boundary]
          data (ex-data-of
                #(validate-ffi-handlers-var {bad-key (fn [_])}))]
      (is (= :jolt.sim.runtime/invalid-config (:type data))
          (pr-str bad-boundary))))
  ;; A boundary equal to the argument-type count is the last valid position.
  (let [max-key [:foreign-function "s" [:int :int] :int false true 2]
        handler (fn [_] :ok)]
    (is (= {max-key handler}
           (validate-ffi-handlers-var {max-key handler})))))

(deftest ambiguous-shorthand-spellings-are-rejected
  ;; The five-element shorthand, its six-element capture?-false equivalent,
  ;; and the seven-element nil-boundary key all canonicalize to the same
  ;; identity; supplying any two in one config is rejected rather than
  ;; silently overwriting one handler with the other.
  (let [canonical [:foreign-function "s" [:int] :int false false nil]
        five-key [:foreign-function "s" [:int] :int false]
        six-key (conj five-key false)
        seven-key (conj six-key nil)]
    (doseq [[label a b] [[:five-and-six five-key six-key]
                         [:five-and-seven five-key seven-key]
                         [:six-and-seven six-key seven-key]]]
      (let [data (ex-data-of
                  #(validate-ffi-handlers-var {a (fn [_] :first)
                                               b (fn [_] :second)}))]
        (is (= :jolt.sim.runtime/invalid-config (:type data)) (pr-str label))
        (is (= [canonical] (:ambiguous-keys data)) (pr-str label))))
    ;; All three together also collide.
    (let [data (ex-data-of
                #(validate-ffi-handlers-var {five-key (fn [_] :a)
                                             six-key (fn [_] :b)
                                             seven-key (fn [_] :c)}))]
      (is (= :jolt.sim.runtime/invalid-config (:type data)))
      (is (= [canonical] (:ambiguous-keys data)))))
  ;; The shorthand together with a six-element *true* capture key is a distinct
  ;; signature (different capture identity), so it is never ambiguous.
  (let [five-key [:foreign-function "s" [:int] :int false]
        six-key-true (conj five-key true)
        canonical (validate-ffi-handlers-var {five-key (fn [_] :a)
                                              six-key-true (fn [_] :b)})]
    (is (= #{[:foreign-function "s" [:int] :int false false nil]
             [:foreign-function "s" [:int] :int false true nil]}
           (set (keys canonical))))))

(deftest five-element-handler-shorthand-matches-capture-false-descriptor
  (let [five-key [:foreign-function "s" [:int] :int false]
        handlers (validate-ffi-handlers-var {five-key (fn [_] :matched)})
        descriptor (assoc base-foreign-function-descriptor
                          :capture-native-error? false)
        state (atom {:ffi-errors []})
        effects (atom [])
        controller (make-ffi-controller-var handlers state effects)]
    (is (= [:foreign-function "s" [:int] :int false false nil]
           (descriptor-handler-key-var descriptor)))
    (is (= :matched (controller descriptor ignored-proceed)))
    (is (empty? (:ffi-errors @state)))))

(deftest fixed-and-variadic-calls-produce-distinct-canonical-keys
  ;; Two otherwise-identical foreign calls differing only in :varargs-after
  ;; select distinct handlers; nil canonicalizes exactly so the fixed-arity
  ;; descriptor matches the nil-boundary key.
  (let [fixed-key (descriptor-handler-key-var
                   (assoc base-foreign-function-descriptor
                          :capture-native-error? false
                          :argument-types [:int :int]
                          :arguments [1 2]))
        variadic-descriptor (assoc base-foreign-function-descriptor
                                   :capture-native-error? false
                                   :argument-types [:int :int]
                                   :arguments [1 2]
                                   :varargs-after 1)
        variadic-key (descriptor-handler-key-var variadic-descriptor)
        state (atom {:ffi-errors []})
        effects (atom [])
        controller (make-ffi-controller-var
                    {fixed-key (fn [_] :fixed)
                     variadic-key (fn [_] :variadic)}
                    state effects)]
    (is (= [:foreign-function "s" [:int :int] :int false false nil] fixed-key))
    (is (= [:foreign-function "s" [:int :int] :int false false 1]
           variadic-key))
    (is (not= fixed-key variadic-key))
    (is (= :fixed (controller (assoc base-foreign-function-descriptor
                                     :capture-native-error? false
                                     :argument-types [:int :int]
                                     :arguments [1 2])
                              ignored-proceed)))
    (is (= :variadic (controller variadic-descriptor ignored-proceed)))
    (is (empty? (:ffi-errors @state)))))

;; ---- Scalar-only foreign argument types (descriptor-version 8) ----------
;;
;; A public foreign argument type is a primitive keyword only; recursive
;; by-value aggregate argument types are rejected. Variadic calls are
;; identified by an exact :varargs-after boundary, not by aggregate types.
;; These are pure structural tests, independent of the running image.

(deftest scalar-foreign-argument-types-are-valid-ffi-descriptors
  (let [descriptor (-> base-foreign-function-descriptor
                       (assoc :argument-types [:int :pointer]
                              :arguments [0 1]
                              :capture-native-error? false))]
    (is (= descriptor (validate-ffi-descriptor-var descriptor)))))

(deftest aggregate-and-non-keyword-argument-types-are-rejected
  (let [reject
        (fn [argument-type]
          (let [descriptor (-> base-foreign-function-descriptor
                               (assoc :argument-types [argument-type]
                                      :capture-native-error? false))
                data (ex-data-of #(validate-ffi-descriptor-var descriptor))]
            (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data))
                (pr-str argument-type))
            (is (= :invalid-argument-types (:reason data))
                (pr-str argument-type))))]
    ;; recursive by-value aggregate (no longer accepted)
    (reject [:by-value [:struct [[:x :int] [:y :pointer]]]])
    ;; bare struct shape
    (reject [:struct [[:x :int]]])
    ;; non-keyword scalar
    (reject "int")
    ;; numeric type tag
    (reject 7))
  (let [bad-key [:foreign-function "make_point"
                 [[:by-value [:struct [[:x :int]]]]] :pointer true false]
        data (ex-data-of #(validate-ffi-handlers-var {bad-key (fn [_])}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= bad-key (:handler-key data))))
  (let [descriptor (-> base-foreign-function-descriptor
                       (assoc :return-type :jolt.sim.test/int
                              :capture-native-error? false))
        data (ex-data-of #(validate-ffi-descriptor-var descriptor))]
    (is (= :jolt.sim.runtime/invalid-ffi-descriptor (:type data)))
    (is (= :invalid-return-type (:reason data)))))

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
    (is (= 42 (controller scalar-descriptor ignored-proceed)))
    (is (= [42 nil] (controller captured-descriptor ignored-proceed)))
    (is (empty? (:ffi-errors @state)))))

(deftest current-controller-dispatches-null-predicate-and-captured-foreign
  ;; The current FFI controller accepts the null? native operation and routes
  ;; it to its handler, still accepts every base operation, and enforces the
  ;; current capture-result contract on captured foreign functions.
  (let [null-descriptor {:kind :native-operation :task 0
                         :arguments [0]
                         :operation :null?}
        base-descriptor {:kind :native-operation :task 0 :arguments [4]
                         :operation :alloc}
        captured-descriptor (assoc base-foreign-function-descriptor
                                   :capture-native-error? true)]
    (let [state (atom {:ffi-errors []})
          effects (atom [])
          controller (make-ffi-controller-var
                      {[:native-operation :null?] (fn [_] true)
                       [:native-operation :alloc] (fn [_] 1042)
                       (descriptor-handler-key-var captured-descriptor)
                       (fn [_] [99 nil])}
                      state effects)]
      (is (= true (controller null-descriptor ignored-proceed)))
      (is (= 1042 (controller base-descriptor ignored-proceed)))
      (is (= [99 nil] (controller captured-descriptor ignored-proceed)))
      (is (= [null-descriptor base-descriptor captured-descriptor]
             (mapv :descriptor @effects)))
      (is (= [:handler :handler :handler]
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
            data (ex-data-of #(controller descriptor ignored-proceed))]
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
          swallowed (try (controller descriptor ignored-proceed) :not-thrown
                        (catch :default _ :caught))]
      (is (= :caught swallowed))
      (is (= 1 (count (:ffi-errors @state)))))
    ;; A two-element vector is accepted.
    (let [state (atom {:ffi-errors []})
          effects (atom [])
          controller (make-ffi-controller-var
                      {key (fn [_] [1 2])} state effects)]
      (is (= [1 2] (controller descriptor ignored-proceed)))
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
    (controller descriptor ignored-proceed)
    (is (identical? descriptor @seen))
    (is (= [descriptor] (mapv :descriptor @effects)))
    (is (= [:hermetic] (mapv :mode @effects)))
    (is (= [:handler] (mapv :route @effects)))))

(deftest current-nested-ffi-descriptor-version-is-exact
  (if (rt/available?)
    (do
      (is (= 8 (get-in (rt/capabilities)
                       [:ffi-interception :descriptor-version])))
      (is (= 1 (get-in (rt/capabilities)
                       [:clock-interception :descriptor-version])))
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
  When omit-supervisor? is true the :supervisor-mono-nanos slot is nil,
  simulating a current image that lacks the supervisor clock var."
  [descriptor omit-supervisor?]
  (let [stub (atom (fn [& _] nil))]
    (cond-> {:capabilities (atom (fn [] descriptor))
             :install-controller! stub
             :restore-controller! stub
             :controller-errors stub
             :clear-controller-errors! stub
             :read-active-byte-array-view stub
             :write-active-byte-array-view! stub}
      (not omit-supervisor?) (assoc :supervisor-mono-nanos stub))))

(defn- mock-controller-ops
  "Builds a complete controller-ops map for the composite install. installed,
  when non-nil, captures the whole composite callback map; ffi-installed and
  clock-installed, when non-nil, capture the :ffi and :clock slots. The private
  ::restore-state key tracks restore-controller! invocations; run-controlled
  ignores it."
  ([descriptor installed]
   (mock-controller-ops descriptor installed nil nil))
  ([descriptor installed ffi-installed clock-installed]
   (let [state (atom {:restores 0 :tokens []})]
     {:descriptor descriptor
      :clear-controller-errors! (fn [])
      :install-controller!
      (fn [composite]
        (when installed (reset! installed composite))
        (when ffi-installed (reset! ffi-installed (:ffi composite)))
        (when clock-installed (reset! clock-installed (:clock composite)))
        :composite-token)
      :restore-controller!
      (fn [token]
        (swap! state update :restores inc)
        (swap! state update :tokens conj token))
      :controller-errors (fn [])
      :supervisor-mono-nanos (fn [] 0)
      ::restore-state state})))

(defn- restore-count [ops]
  (:restores @(::restore-state ops)))

(defn- restore-tokens [ops]
  (:tokens @(::restore-state ops)))

(deftest active-byte-array-view-api-delegates-to-the-exact-host-vars
  (let [calls (atom [])
        ops (assoc (mock-controller-ops supported-descriptor nil)
                   :read-active-byte-array-view
                   (fn [ptr len]
                     (swap! calls conj [:read ptr len])
                     (byte-array [1 -1]))
                   :write-active-byte-array-view!
                   (fn [ptr src]
                     (swap! calls conj [:write ptr (vec src)])
                     (alength src)))
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn [] ops)}
      #(do
         (is (= [1 -1] (vec (rt/read-active-byte-array-view 100 2))))
         (is (= 2 (rt/write-active-byte-array-view!
                   101 (byte-array [9 8]))))))
    (is (= [[:read 100 2] [:write 101 [9 8]]] @calls))))

(deftest controlled-active-byte-array-views-reuse-the-validated-run-ops
  ;; The host view is called while Chez owns a locked temporary. The enclosing
  ;; run has already resolved and validated the complete ABI, so view traffic
  ;; must not reconstruct that descriptor in the sensitive loan extent.
  (let [resolve-calls (atom 0)
        view-calls (atom [])
        ops (assoc (mock-controller-ops supported-descriptor nil)
                   :read-active-byte-array-view
                   (fn [ptr len]
                     (swap! view-calls conj [:read ptr len])
                     (byte-array [7]))
                   :write-active-byte-array-view!
                   (fn [ptr src]
                     (swap! view-calls conj [:write ptr (vec src)])
                     (alength src)))
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn []
                     (swap! resolve-calls inc)
                     ops)}
      #(let [result
             (rt/run-controlled
              {}
              (fn []
                [(vec (rt/read-active-byte-array-view 200 1))
                 (rt/write-active-byte-array-view!
                  201 (byte-array [8]))]))]
         (is (= [[7] 1] (:result result)))))
    (is (= 1 @resolve-calls))
    (is (= [[:read 200 1] [:write 201 [8]]] @view-calls))))

(defn- native-descriptor [operation arguments]
  {:kind :native-operation
    :task 0
    :arguments arguments
    :operation operation})

(deftest current-contract-requires-the-supervisor-clock-var
  (let [resolved-var (resolve 'jolt.sim.runtime/resolved-abi-vars)]
    ;; A current image that advertises the exact descriptor but omits the
    ;; supervisor-mono-nanos var is incompatible.
    (let [data
          (with-redefs-fn
            {resolved-var (fn [] (mock-resolved-abi-vars supported-descriptor true))}
            #(ex-data-of rt/capabilities))]
      (is (= :jolt.sim.runtime/abi-incompatible (:type data)))
      (is (= [:supervisor-mono-nanos] (:missing data))))
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

(deftest current-contract-requires-both-active-byte-array-view-vars
  (let [resolved-var (resolve 'jolt.sim.runtime/resolved-abi-vars)]
    (doseq [missing-key [:read-active-byte-array-view
                         :write-active-byte-array-view!]]
      (let [data
            (with-redefs-fn
              {resolved-var
               (fn []
                 (assoc (mock-resolved-abi-vars supported-descriptor false)
                        missing-key nil))}
              #(ex-data-of rt/capabilities))]
        (is (= :jolt.sim.runtime/abi-incompatible (:type data))
            (pr-str missing-key))
        (is (= [missing-key] (:missing data)) (pr-str missing-key))))))

(deftest runtime-resolution-distinguishes-absence-from-incompatible-versions
  (let [resolved-var (resolve 'jolt.sim.runtime/resolved-abi-vars)
        absent {:capabilities nil
                :install-controller! nil
                :restore-controller! nil
                :controller-errors nil
                :clear-controller-errors! nil
                :supervisor-mono-nanos nil
                :read-active-byte-array-view nil
                :write-active-byte-array-view! nil}]
    (with-redefs-fn
      {resolved-var (fn [] absent)}
      #(do
         (is (false? (rt/available?)))
         (is (= :jolt.sim.runtime/abi-unavailable
                (:type (ex-data-of rt/capabilities))))))
    (doseq [version [3 4 5 7 8]]
      (let [descriptor (assoc supported-descriptor :abi-version version)
            data
            (with-redefs-fn
              {resolved-var
               (fn [] (mock-resolved-abi-vars descriptor false))}
              #(ex-data-of rt/available?))]
        (is (= :jolt.sim.runtime/abi-incompatible (:type data))
            (pr-str version))))))

(deftest composite-install-is-atomic-with-one-restore-token
  ;; The composite map {:future :ffi :clock} is installed by a single
  ;; install-controller! call capturing one token, and restored by a single
  ;; restore-controller! call over that exact token. A thunk that spawns
  ;; nothing drains immediately and restores exactly once.
  (let [installed (atom nil)
        ffi-installed (atom nil)
        clock-installed (atom nil)
        ops (mock-controller-ops supported-descriptor
                                 installed ffi-installed clock-installed)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn [] ops)}
      #(let [result (rt/run-controlled {} (fn [] :done))]
         (is (= :done (:result result)))
         (is (= supported-descriptor (:capabilities result)))
         (is (map? @installed)
             "one composite map must be installed")
         (is (= #{:future :ffi :clock} (set (keys @installed))))
         (is (ifn? (:future @installed))
             "the future lifecycle controller is installed")
         (is (identical? (:ffi @installed) @ffi-installed)
             "the established FFI controller is the composite :ffi slot")
         (is (identical? (:clock @installed) @clock-installed)
             "the default clock controller is the composite :clock slot")
         (is (ifn? @clock-installed))
         (is (= 1 (restore-count ops))
             "restore-controller! must be called exactly once")
         (is (= [:composite-token] (restore-tokens ops))
             "the single composite token is restored")))))

(deftest failed-atomic-install-restores-no-nil-token-and-leaves-the-session-reusable
  (let [good-ops (mock-controller-ops supported-descriptor nil)
        bad-restores (atom 0)
        bad-ops (assoc good-ops
                       :install-controller!
                       (fn [_]
                         (throw (ex-info "atomic install rejected" {:phase :install})))
                       :restore-controller!
                       (fn [_] (swap! bad-restores inc)))
        selected (atom bad-ops)
        local-session (atom :idle)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        session-var (resolve 'jolt.sim.runtime/session-state)]
    (with-redefs-fn
      {resolve-var (fn [] @selected)
       session-var local-session}
      #(do
         (is (= :install
                (:phase (ex-data-of
                         (fn [] (rt/run-controlled {} (fn [] :never)))))))
         (is (zero? @bad-restores)
             "a failed atomic install has no token to restore")
         (is (= :idle @local-session))
         (reset! selected good-ops)
         (is (= :reused
                (:result (rt/run-controlled {} (fn [] :reused)))))
         (is (= 1 (restore-count good-ops)))))))

(deftest default-clock-controller-proceeds-so-real-os-time-is-available
  ;; With no :clock configured, the installed clock callback is arity 2 and
  ;; proceeds every intercepted operation, returning whatever the real native
  ;; clock branch produces.
  (let [clock-installed (atom nil)
        ops (mock-controller-ops supported-descriptor nil nil clock-installed)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn [] ops)}
      #(let [result (rt/run-controlled {} (fn [] :done))]
         (is (= :done (:result result)))
         (is (ifn? @clock-installed))
         (let [proceeded (atom 0)]
           (is (= 7300000000
                  (@clock-installed
                   {:kind :clock :operation :mono-nanos}
                   (fn [] (swap! proceeded inc) 7300000000))))
           (is (= 1 @proceeded)
               "the default pass-through clock must invoke proceed"))))))

(deftest a-modeled-frozen-clock-validates-the-live-descriptor-and-does-not-proceed
  ;; A user :clock controller is wrapped by the adapter's exact live-descriptor
  ;; validator before it becomes the composite :clock slot. Valid calls reach
  ;; user code without touching the real native clock branch; malformed calls
  ;; fail before user code or proceed.
  (let [clock-installed (atom nil)
        seen (atom [])
        frozen (fn [descriptor _proceed]
                 (swap! seen conj descriptor)
                 9999999)
        ops (mock-controller-ops supported-descriptor nil nil clock-installed)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn [] ops)}
      #(let [result (rt/run-controlled {:clock frozen} (fn [] :done))]
         (is (= :done (:result result)))
         (is (fn? @clock-installed))
         (let [proceeded (atom 0)]
           (is (= 9999999
                  (@clock-installed
                   {:kind :clock :operation :mono-nanos}
                   (fn [] (swap! proceeded inc) :wrong))))
           (is (zero? @proceeded)
               "a frozen clock must not invoke the native proceed")
           (is (= [{:kind :clock :operation :mono-nanos}] @seen)))
         (doseq [bad [nil
                      {:kind :clock :operation :unknown}
                      {:kind :other :operation :mono-nanos}
                      {:kind :clock :operation :mono-nanos :extra true}]]
           (let [data (ex-data-of #(@clock-installed bad (fn [] :native)))]
             (is (= :jolt.sim.runtime/invalid-clock-descriptor (:type data)))))
         (is (= 1 (count @seen))
             "invalid live descriptors must not reach the user controller")))))

(deftest an-invalid-clock-config-fails-before-abi-resolution
  ;; :clock must be a two-argument controller function; a non-function value is
  ;; rejected as pure config before any controller is resolved or installed.
  (let [data (ex-data-of #(rt/run-controlled {:clock :not-a-fn}
                                             (fn [] :uncontrolled)))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= :not-a-fn (:clock data)))))

(deftest drain-deadlines-use-the-supervisor-clock-not-system-nanotime
  ;; Drain deadlines must read the resolved private supervisor-mono-nanos so a
  ;; frozen virtual clock still times out. A controlled run whose body drains
  ;; immediately still consults the supervisor clock to compute its deadline.
  (let [supervisor-calls (atom 0)
        ops (assoc (mock-controller-ops supported-descriptor nil)
                   :supervisor-mono-nanos
                   (fn [] (swap! supervisor-calls inc) 0))
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn [] ops)}
      #(let [result (rt/run-controlled {} (fn [] :done))]
         (is (= :done (:result result)))
         (is (pos? @supervisor-calls)
             "drain must read supervisor-mono-nanos rather than System/nanoTime"))))
  ;; A frozen user clock must not stall drainage: the supervisor clock still
  ;; advances the deadline so a quiescent scope restores normally.
  (let [frozen-calls (atom 0)
        ops (mock-controller-ops supported-descriptor nil)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)]
    (with-redefs-fn
      {resolve-var (fn [] ops)}
      #(let [result
             (rt/run-controlled
              {:clock (fn [_ _] (swap! frozen-calls inc) 12345)}
              (fn [] :done))]
         (is (= :done (:result result)))))))

(deftest observe-installs-routing-proceeds-once-and-records-the-route
  (let [installed (atom nil)
        ffi-installed (atom nil)
        proceed-count (atom 0)
        descriptor (native-descriptor :sizeof [:int])
        ops (mock-controller-ops supported-descriptor
                                 installed ffi-installed nil)
        resolve-var (resolve 'jolt.sim.runtime/resolve-controller-ops!)
        run
        (with-redefs-fn
          {resolve-var (fn [] ops)}
          #(rt/run-controlled
            {:ffi-mode :observe}
            (fn []
              ((deref ffi-installed)
               descriptor
               (fn [] (swap! proceed-count inc) 8)))))]
    (is (= 8 (:result run)))
    (is (= 1 @proceed-count))
    (is (some? @ffi-installed)
        "observe installs the routing controller as the composite :ffi slot")
    (is (identical? @ffi-installed (:ffi @installed)))
    (is (= [descriptor] (:effects run)))
    (is (= [{:mode :observe :route :native :descriptor descriptor}]
           (:effect-trace run)))
    (is (= 1 (restore-count ops)))))

(deftest observe-preserves-a-caught-native-proceed-exception
  (let [routing (atom nil)
        descriptor (native-descriptor :sizeof [:int])
        ops (mock-controller-ops supported-descriptor nil routing nil)
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
        ops (mock-controller-ops supported-descriptor nil routing nil)
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
        ops (mock-controller-ops supported-descriptor nil routing nil)
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
         :blocking? false :capture-native-error? false :varargs-after nil}
        foreign-void-pointer (assoc foreign-pointer :return-type :void*)
        alloc (native-descriptor :alloc [8])
        string-pointer (native-descriptor :string->ptr ["x"])
        pointer-descriptors
        [alloc
         (native-descriptor :read [5000 :pointer 0])
         (native-descriptor :read [5000 :void* 0])
         foreign-pointer
         foreign-void-pointer]
        fixed-native-descriptors [alloc string-pointer]]
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
          (is (= :resource-requires-modeled-resource
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
         :blocking? false :capture-native-error? false :varargs-after nil}
        key (descriptor-handler-key-var descriptor)
        controller
        (make-ffi-routing-controller-var
         :hybrid {key (fn [_] (rt/substitute -1))}
         state (atom []) (atom []))]
    (is (= -1 (controller descriptor (fn [] :wrong))))
    (is (empty? (:ffi-errors @state)))))

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

;; ---- Pointer-bearing operand-position provenance -----------------------

(deftest hybrid-pointer-domain-resource-is-checked-only-at-pointer-bearing-positions
  ;; read-bytes takes [ptr len]: only position 0 is a pointer. A low fake
  ;; pointer's exact value colliding with an ordinary length argument must not
  ;; block a call whose own pointer argument is unrelated; the same value in
  ;; the pointer position must still block, including an interior alias.
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        alloc-key [:native-operation :alloc]
        base 4
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {alloc-key (fn [_] (rt/modeled-resource base 8))}
         state effect-trace ledger)]
    (is (= base (controller (native-descriptor :alloc [8]) (fn [] :wrong))))
    ;; base appears only as the ordinary :len argument (position 1); the
    ;; unrelated :ptr argument (position 0) proceeds to native.
    (let [proceeded? (atom false)
          result
          (controller (native-descriptor :read-bytes [999 base])
                      (fn [] (reset! proceeded? true) "native-bytes"))]
      (is (= "native-bytes" result))
      (is (true? @proceeded?)))
    ;; base appears as the :ptr argument (position 0): blocked.
    (let [proceeded? (atom false)
          data (ex-data-of
                #(controller (native-descriptor :read-bytes [base 5])
                             (fn [] (reset! proceeded? true) "wrong")))]
      (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data)))
      (is (false? @proceeded?)))
    ;; An interior alias of the pointer position (base+3, within [base,
    ;; base+8)) is still blocked.
    (let [proceeded? (atom false)
          data (ex-data-of
                #(controller (native-descriptor :read-bytes [(+ base 3) 5])
                             (fn [] (reset! proceeded? true) "wrong")))]
      (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data)))
      (is (false? @proceeded?)))))

(deftest hybrid-write-value-slot-is-checked-for-every-pointer-capable-type
  ;; write takes [ptr type offset value]. value (position 3) is a pointer
  ;; position when type (position 1) is :pointer/:void*/:iptr/:uptr; an
  ;; ordinary scalar write of the same numeric value must proceed. Native
  ;; read/write also accept those types by string spelling.
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        alloc-key [:native-operation :alloc]
        base 4
        other-ptr 2000
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {alloc-key (fn [_] (rt/modeled-resource base 8))}
         state effect-trace ledger)]
    (is (= base (controller (native-descriptor :alloc [8]) (fn [] :wrong))))
    (let [proceeded? (atom false)
          result
          (controller (native-descriptor :write [other-ptr :int 0 base])
                      (fn [] (reset! proceeded? true) :native))]
      (is (= :native result))
      (is (true? @proceeded?)))
    (doseq [pointer-type [:pointer :void* :iptr :uptr
                          "pointer" "void*" "iptr" "uptr"]]
      (let [proceeded? (atom false)
            data
            (ex-data-of
             #(controller
               (native-descriptor :write
                                  [other-ptr pointer-type 0 base])
               (fn [] (reset! proceeded? true) :wrong)))]
        (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data))
            (pr-str pointer-type))
        (is (false? @proceeded?) (pr-str pointer-type))))))

(deftest hybrid-foreign-pointer-capable-positions-derive-from-argument-types
  ;; A foreign call typed [:int pointer-capable] must check only its position-1
  ;; argument against pointer-domain resources; base at position 0 (the :int
  ;; slot) proceeds, the identical value at position 1 blocks. :iptr/:uptr are
  ;; scalar return domains, but their operand slots can carry pointer bits.
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        alloc-key [:native-operation :alloc]
        base 4
        foreign-descriptor
        (fn [pointer-type arguments]
          {:kind :foreign-function :task 0 :arguments arguments
           :symbol "takes_int_and_pointer"
           :argument-types [:int pointer-type]
           :return-type :int :blocking? false
           :capture-native-error? false :varargs-after nil})
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {alloc-key (fn [_] (rt/modeled-resource base 8))}
         state effect-trace ledger)]
    (is (= base (controller (native-descriptor :alloc [8]) (fn [] :wrong))))
    (doseq [pointer-type [:pointer :void* :iptr :uptr]]
      (let [proceeded? (atom false)
            result
            (controller (foreign-descriptor pointer-type [base 42])
                        (fn [] (reset! proceeded? true) 7))]
        (is (= 7 result) (pr-str pointer-type))
        (is (true? @proceeded?) (pr-str pointer-type)))
      (let [proceeded? (atom false)
            data
            (ex-data-of
             #(controller (foreign-descriptor pointer-type [42 base])
                          (fn [] (reset! proceeded? true) :wrong)))]
        (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data))
            (pr-str pointer-type))
        (is (false? @proceeded?) (pr-str pointer-type))))))

(deftest hybrid-opaque-domain-resource-remains-checked-at-every-position
  ;; A handler pack may still classify a scalar handle the ABI types cannot
  ;; identify as a pointer (e.g. a plain :int return type) as a
  ;; modeled-resource. That resource's domain is :opaque, so it stays
  ;; conservatively checked against every argument position, matching prior
  ;; behavior -- including a position :argument-types marks a plain :int.
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        handle-descriptor
        {:kind :foreign-function :task 0 :arguments []
         :symbol "opaque_handle" :argument-types [] :return-type :int
         :blocking? false :capture-native-error? false :varargs-after nil}
        handle-key (descriptor-handler-key-var handle-descriptor)
        base 9000
        use-descriptor
        {:kind :foreign-function :task 0 :arguments [base]
         :symbol "use_int_handle" :argument-types [:int] :return-type :int
         :blocking? false :capture-native-error? false :varargs-after nil}
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {handle-key (fn [_] (rt/modeled-resource base 1))}
         state effect-trace ledger)]
    (is (= base (controller handle-descriptor (fn [] :wrong))))
    (is (= :opaque (:domain (first @ledger))))
    (let [proceeded? (atom false)
          data (ex-data-of
                #(controller use-descriptor
                             (fn [] (reset! proceeded? true) :wrong)))]
      (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data)))
      (is (false? @proceeded?)))))

(deftest hybrid-null-predicate-never-crosses-a-modeled-resource-boundary
  ;; null? only truncates and compares its numeric argument with zero. It does
  ;; not dereference the value or reach the OS, so both an unhandled hybrid
  ;; miss and an explicitly selected native proceed are safe even when the
  ;; numeric value names a pointer-domain or opaque modeled resource.
  (let [base 9000
        pointer-descriptor (native-descriptor :alloc [8])
        pointer-key [:native-operation :alloc]
        opaque-descriptor
        {:kind :foreign-function :task 0 :arguments []
         :symbol "opaque_handle_for_null_test" :argument-types []
         :return-type :int :blocking? false
         :capture-native-error? false :varargs-after nil}
        opaque-key (descriptor-handler-key-var opaque-descriptor)
        null-descriptor (native-descriptor :null? [base])
        null-key [:native-operation :null?]]
    (doseq [[expected-domain producer-descriptor producer-key]
            [[:pointer pointer-descriptor pointer-key]
             [:opaque opaque-descriptor opaque-key]]
            selected? [false true]]
      (let [state (atom {:ffi-errors []})
            effect-trace (atom [])
            ledger (atom [])
            proceeded? (atom false)
            handlers
            (cond->
             {producer-key (fn [_] (rt/modeled-resource base 8))}
              selected? (assoc null-key (fn [_] (rt/proceed))))
            controller
            (make-ffi-routing-controller-var
             :hybrid handlers state effect-trace ledger)]
        (is (= base (controller producer-descriptor (fn [] :wrong))))
        (is (= expected-domain (:domain (first @ledger))))
        (is (false?
             (controller null-descriptor
                         (fn [] (reset! proceeded? true) false))))
        (is (true? @proceeded?))
        (is (= :native (:route (peek @effect-trace))))
        (is (= (when selected? null-key)
               (:handler-key (peek @effect-trace))))
        (is (empty? (:ffi-errors @state)))))))

(deftest hybrid-caught-inexact-resource-alias-remains-a-controller-error
  (let [routing (atom nil)
        fake-base 9007199254740993
        alias (+ fake-base 1/2)
        alloc (native-descriptor :alloc [8])
        free-alias (native-descriptor :free [alias])
        ops (mock-controller-ops supported-descriptor nil routing nil)
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
         :blocking? false :capture-native-error? true :varargs-after nil}
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

;; ---- Selected native proceed and additional modeled resources ---------

(deftest hybrid-selected-proceed-routes-to-native-and-keeps-handler-identity
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        proceeded? (atom false)
        key [:native-operation :sizeof]
        descriptor (native-descriptor :sizeof [:int])
        controller
        (make-ffi-routing-controller-var
         :hybrid {key (fn [_] (rt/proceed))} state effect-trace ledger)
        result (controller descriptor
                           (fn [] (reset! proceeded? true) 4))]
    (is (= 4 result))
    (is (true? @proceeded?))
    (is (= [{:mode :hybrid :route :native
             :handler-key key :descriptor descriptor}]
           @effect-trace))
    (is (empty? (:ffi-errors @state)))
    (is (empty? @ledger))))

(deftest hybrid-selected-proceed-is-blocked-by-prior-modeled-provenance
  ;; A handler that selects proceed still passes through the existing
  ;; provenance guard first; proceed is never invoked once a call's live
  ;; arguments alias an already-registered modeled resource.
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        alloc-descriptor (native-descriptor :alloc [8])
        free-descriptor (native-descriptor :free [1042000000])
        free-key [:native-operation :free]
        proceeded? (atom false)
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {[:native-operation :alloc]
          (fn [_] (rt/modeled-resource 1042000000 8))
          free-key
          (fn [_] (rt/proceed))}
         state effect-trace ledger)]
    (is (= 1042000000 (controller alloc-descriptor (fn [] :wrong))))
    (let [data
          (ex-data-of
           #(controller free-descriptor
                        (fn [] (reset! proceeded? true) nil)))]
      (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data)))
      (is (false? @proceeded?))
      (is (= [:handler :blocked] (mapv :route @effect-trace)))
      (is (= free-key (:handler-key (second @effect-trace))))
      (is (= :modeled-resource-native-fallback
             (:ffi-error (first (:ffi-errors @state))))))))

(deftest selected-proceed-is-invalid-in-hermetic-mode
  (let [state (atom {:ffi-errors []})
        effects (atom [])
        descriptor (native-descriptor :sizeof [:int])
        controller
        (make-ffi-controller-var
         {[:native-operation :sizeof] (fn [_] (rt/proceed))}
         state effects)
        data (ex-data-of #(controller descriptor ignored-proceed))]
    (is (= :jolt.sim.runtime/invalid-handler-result (:type data)))
    (is (= :proceed-requires-hybrid-mode (:reason data)))
    (is (= :invalid-handler-result (:ffi-error (first (:ffi-errors @state)))))))

(deftest hybrid-selected-proceed-passes-through-a-captured-native-result
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        descriptor
        {:kind :foreign-function :task 0 :arguments []
         :symbol "pipe_status" :argument-types [] :return-type :int
         :blocking? false :capture-native-error? true :varargs-after nil}
        key (descriptor-handler-key-var descriptor)
        controller
        (make-ffi-routing-controller-var
         :hybrid {key (fn [_] (rt/proceed))} state effect-trace ledger)
        result (controller descriptor (fn [] [0 nil]))]
    (is (= [0 nil] result))
    (is (= [{:mode :hybrid :route :native
             :handler-key key :descriptor descriptor}]
           @effect-trace))
    (is (empty? (:ffi-errors @state)))))

(deftest hybrid-selected-proceed-native-exception-remains-catchable
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        key [:native-operation :sizeof]
        descriptor (native-descriptor :sizeof [:int])
        controller
        (make-ffi-routing-controller-var
         :hybrid {key (fn [_] (rt/proceed))} state effect-trace ledger)
        caught
        (try
          (controller descriptor
                      (fn [] (throw (ex-info "native failure" {:source :native}))))
          :not-thrown
          (catch :default error (:source (ex-data error))))]
    (is (= :native caught))
    (is (empty? (:ffi-errors @state))
        "a native proceed exception must not be latched as a controller error")
    (is (= [{:mode :hybrid :route :native
             :handler-key key :descriptor descriptor}]
           @effect-trace))))

(deftest hybrid-effect-trace-preserves-arrival-order-across-a-slow-selected-proceed
  ;; Two concurrent handler calls arrive in order A then B, but A's handler
  ;; (which selects proceed) only completes after B's (which substitutes)
  ;; finishes. The reserved effect-trace slot for A is finalized in place, so
  ;; its position still reflects arrival order rather than completion order.
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        a-started (promise)
        release-a (promise)
        a-proceeded? (atom false)
        alloc-key [:native-operation :alloc]
        sizeof-key [:native-operation :sizeof]
        a-descriptor (native-descriptor :alloc [8])
        b-descriptor (native-descriptor :sizeof [:int])
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {alloc-key
          (fn [_]
            (deliver a-started true)
            @release-a
            (rt/proceed))
          sizeof-key
          (fn [_] (rt/substitute 4))}
         state effect-trace ledger)
        a-result (promise)
        a-thread
        (Thread.
         (fn []
           (deliver
            a-result
            (try
              {:value
               (controller a-descriptor
                           (fn [] (reset! a-proceeded? true) :native-a))}
              (catch :default error
                {:error error})))))]
    ;; The assertions and release below still bound and join the worker. Make
    ;; it daemon as a final test-harness guard so a controller deadlock cannot
    ;; retain the entire shared suite after those bounds report failure.
    (.setDaemon a-thread true)
    (.start a-thread)
    (try
      (is (not= :timeout (deref a-started 5000 :timeout)))
      (let [b-result (controller b-descriptor (fn [] :wrong-b))]
        (is (= 4 b-result)))
      (finally
        (deliver release-a true)
        (.join a-thread 5000)))
    (is (= {:value :native-a} (deref a-result 5000 :timeout)))
    (is (true? @a-proceeded?))
    (is (= [a-descriptor b-descriptor] (mapv :descriptor @effect-trace)))
    (is (= [:native :handler] (mapv :route @effect-trace)))
    (is (= alloc-key (:handler-key (first @effect-trace))))))

(deftest hybrid-additional-resources-block-later-native-misses
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        alloc-key [:native-operation :alloc]
        pipe-descriptor (native-descriptor :alloc [8])
        read-fd-descriptor (native-descriptor :free [9000])
        write-fd-descriptor (native-descriptor :free [9100])
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {alloc-key
          (fn [_]
            (rt/with-additional-resources
             (rt/substitute 0)
             [{:base 9000 :span 1} {:base 9100 :span 1}]))}
         state effect-trace ledger)]
    (is (= 0 (controller pipe-descriptor (fn [] :wrong))))
    (is (= [{:base 9000 :span 1 :handler-key alloc-key :descriptor pipe-descriptor
             :domain :opaque}
            {:base 9100 :span 1 :handler-key alloc-key :descriptor pipe-descriptor
             :domain :opaque}]
           @ledger))
    (doseq [descriptor [read-fd-descriptor write-fd-descriptor]]
      (let [data (ex-data-of #(controller descriptor (fn [] :wrong)))]
        (is (= :jolt.sim.runtime/modeled-resource-native-fallback (:type data))
            (pr-str descriptor))))))

(deftest with-additional-resources-validates-eagerly-and-rejects-bad-targets
  (let [data (ex-data-of
              #(rt/with-additional-resources
                (rt/substitute 0)
                [{:base 0 :span 1} {:base -1 :span 1}]))]
    (is (= :jolt.sim.runtime/invalid-modeled-resource (:type data))))
  (let [data (ex-data-of
              #(rt/with-additional-resources
                (rt/substitute 0)
                [{:base 0 :span 0}]))]
    (is (= :jolt.sim.runtime/invalid-modeled-resource (:type data))))
  (let [data (ex-data-of #(rt/with-additional-resources 42 []))]
    (is (= :jolt.sim.runtime/invalid-handler-result (:type data)))
    (is (= :additional-resources-target (:reason data))))
  (let [data (ex-data-of #(rt/with-additional-resources (rt/proceed) []))]
    (is (= :jolt.sim.runtime/invalid-handler-result (:type data)))
    (is (= :additional-resources-target (:reason data))))
  (let [data
        (ex-data-of
         #(rt/with-additional-resources
           (assoc (rt/substitute 0) :unexpected true)
           []))]
    (is (= :jolt.sim.runtime/invalid-handler-result (:type data)))
    (is (= :additional-resources-target (:reason data))))
  (let [data
        (ex-data-of
         #(rt/with-additional-resources
           (rt/substitute 0)
           (list {:base 0 :span 1})))]
    (is (= :jolt.sim.runtime/invalid-modeled-resource (:type data)))
    (is (= :not-a-vector (:reason data))))
  ;; Zero additional resources is a valid, no-op composition: wrapping still
  ;; decodes and dispatches as an ordinary substitute.
  (let [state (atom {:ffi-errors []})
        effect-trace (atom [])
        ledger (atom [])
        key [:native-operation :sizeof]
        descriptor (native-descriptor :sizeof [:int])
        controller
        (make-ffi-routing-controller-var
         :hybrid
         {key (fn [_] (rt/with-additional-resources (rt/substitute 4) []))}
         state effect-trace ledger)]
    (is (= 4 (controller descriptor (fn [] :wrong))))
    (is (empty? @ledger))))

(deftest malformed-additional-resources-leave-the-ledger-unchanged
  ;; A hand-built decoded map exercises register-modeled-resource!'s own
  ;; defensive validation directly, independent of with-additional-resources'
  ;; eager checks. A valid primary plus one valid and one malformed addition
  ;; must leave the ledger with none of the three appended.
  (let [ledger (atom [])
        descriptor (native-descriptor :alloc [8])
        decoded {:kind :modeled-resource :value 1042000000 :span 8
                 :additional-resources
                 [{:base 9000 :span 1} {:base -1 :span 1}]}
        data (ex-data-of
              #(register-modeled-resource-var
                ledger descriptor [:native-operation :alloc] decoded))]
    (is (= :jolt.sim.runtime/invalid-modeled-resource (:type data)))
    (is (empty? @ledger)
        "a malformed later addition must not leave earlier additions or the primary resource behind")))

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
