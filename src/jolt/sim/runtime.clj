(ns jolt.sim.runtime
  "Test-only adapter that bridges ordinary Jolt code to the optional simulation
  controller exposed by a sim-enabled Jolt image.

  Core Jolt exposes sim-image-only vars in the namespace jolt.internal.sim:

    capabilities             ;; () -> descriptor (ABI v1 or v2, see below)
    install-controller!      ;; (controller) -> opaque token
    restore-controller!      ;; (token) -> nil, restores exactly what that
                              ;; token displaced; only the current top of the
                              ;; strict-LIFO installation stack may restore
    controller-errors        ;; () -> latched controller hook errors
    clear-controller-errors! ;; () -> nil, clears latched errors

  ABI v2 additionally exposes:

    install-ffi-controller!  ;; (ffi-controller) -> opaque token
    restore-ffi-controller!  ;; (token) -> nil, LIFO-restores the FFI controller

  The controller fn install-controller! installs is invoked positionally as
  (controller event id parent) on the future lifecycle hook thread — event is
  one of :spawn/:start/:finish/:cancel, id is that task's stable id, parent is
  the task id in effect on the spawning thread. A task's lifetime begins at
  :spawn and terminates at :finish or :cancel. A :spawn hook failure propagates
  before the worker forks, while a :start hook failure becomes that future's
  own failure. :finish/:cancel hook failures never replace the application
  result — they are latched in controller-errors instead.

  Those vars exist only in a sim image; an ordinary released image has no such
  namespace. This adapter resolves the symbols dynamically (resolve on quoted
  symbols, never a compile-time require) so that this namespace loads and its
  tests run normally under released ordinary Jolt, where the controller is
  simply reported absent rather than failing to compile.

  ABI v1 descriptor (lifecycle only):

    {:abi-version 1 :future-lifecycle true :controller-errors true
     :events [:spawn :start :finish :cancel]}

  ABI v2 descriptor adds an FFI interception capability:

    {:abi-version 2 :future-lifecycle true :controller-errors true
     :events [:spawn :start :finish :cancel]
     :ffi-interception {:descriptor-version 1
                        :kinds [:foreign-function :native-operation]
                        :arguments :live
                        :task-identity :future-lifecycle
                        :native-operations [:load-library :loaded? :alloc :free
                                            :read :write :sizeof :read-bytes
                                            :write-bytes :read-array :write-array
                                            :ptr->string :string->ptr]}}

  Under v2, run-controlled installs the FFI controller on every run even when
  no handlers are configured. A native effect inside the scope that has no
  registered handler throws :jolt.sim.runtime/unhandled-native-effect before
  the OS is reached. Handlers are configured via :ffi-handlers, a map keyed by
  [:native-operation operation] or [:foreign-function symbol argument-types
  return-type blocking?]; map membership defines handled, so a nil value is a
  valid substitute. Handler and missing-handler failures are latched locally so
  application code cannot catch them and make the run succeed.

  Scope of v1/v2. run-controlled observes task starts and lets :on-event block
  at :start to gate them. It records an ordered event log of exact
  {:event :task :parent} maps, admits one exclusive run at a time, and fails
  closed on controller errors and on tasks that outlive the controlled scope.
  Under v2 it additionally records an ordered :effects log of every validated
  native interception in arrival order. It is NOT yet an exhaustive
  deterministic scheduler: it does not reorder execution, advance virtual time,
  inject faults, or bind a unified causal trace between lifecycle events and
  native effects. Those remain future work tracked in the project README."
  (:require [jolt.sim.trace :as trace]))

(def ^:private v1-descriptor
  {:abi-version 1
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel]})

(def ^:private v2-descriptor
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

(def ^:private supported-events
  (set (:events v1-descriptor)))

(def ^:private native-operations
  (set (get-in v2-descriptor [:ffi-interception :native-operations])))

(def ^:private ffi-kinds
  (set (get-in v2-descriptor [:ffi-interception :kinds])))

(def ^:private capabilities-sym 'jolt.internal.sim/capabilities)
(def ^:private install-sym 'jolt.internal.sim/install-controller!)
(def ^:private restore-sym 'jolt.internal.sim/restore-controller!)
(def ^:private errors-sym 'jolt.internal.sim/controller-errors)
(def ^:private clear-errors-sym 'jolt.internal.sim/clear-controller-errors!)
(def ^:private install-ffi-sym 'jolt.internal.sim/install-ffi-controller!)
(def ^:private restore-ffi-sym 'jolt.internal.sim/restore-ffi-controller!)

(def ^:private base-abi-keys
  [:capabilities
   :install-controller!
   :restore-controller!
   :controller-errors
   :clear-controller-errors!])

(def ^:private ffi-abi-keys
  [:install-ffi-controller!
   :restore-ffi-controller!])

;; Single run-controlled session state. Compare-and-set! claims :idle
;; atomically, so overlapping or nested runs fail closed without a separate
;; lock. A failed exact-token restore leaves the adapter :poisoned: another run
;; must not proceed over controller state whose ownership is no longer known.
(def ^:private session-state (atom :idle))

(defn- safe-resolve [sym]
  (try (resolve sym) (catch :default _ nil)))

(defn- resolved-abi-vars
  "Returns controller ABI resolution results, including nils. The five base
  vars are required for any sim image; the two FFI vars are required only for
  ABI v2."
  []
  {:capabilities (safe-resolve capabilities-sym)
   :install-controller! (safe-resolve install-sym)
   :restore-controller! (safe-resolve restore-sym)
   :controller-errors (safe-resolve errors-sym)
   :clear-controller-errors! (safe-resolve clear-errors-sym)
   :install-ffi-controller! (safe-resolve install-ffi-sym)
   :restore-ffi-controller! (safe-resolve restore-ffi-sym)})

(defn available?
  "True only on a sim-enabled Jolt image that exposes the base controller ABI
  (the five lifecycle vars). Returns false on an ordinary released image
  without throwing. The FFI vars are validated separately for v2."
  []
  (let [vars (resolved-abi-vars)]
    (every? some? (map vars base-abi-keys))))

(defn- validate-descriptor [caps-value]
  (when-not (map? caps-value)
    (throw
     (ex-info "jolt.internal.sim/capabilities did not return a map"
              {:type :jolt.sim.runtime/abi-incompatible
               :capabilities-class (str (class caps-value))})))
  (when-not (or (= v1-descriptor caps-value)
                (= v2-descriptor caps-value))
    (throw
     (ex-info "jolt.internal.sim exposes an incompatible controller ABI"
              {:type :jolt.sim.runtime/abi-incompatible
               :expected [v1-descriptor v2-descriptor]
               :actual caps-value})))
  caps-value)

(defn- resolve-controller-ops!
  "Resolves the controller ABI vars and the validated capabilities descriptor,
  or throws :jolt.sim.runtime/abi-unavailable (no sim image) or
  :jolt.sim.runtime/abi-incompatible (unsupported/malformed descriptor or a v2
  descriptor whose FFI vars are missing)."
  []
  (let [vars (resolved-abi-vars)
        base-missing
        (vec
          (keep (fn [key]
                  (when-not (vars key) key))
                base-abi-keys))]
    (cond
      (= (count base-missing) (count base-abi-keys))
      (throw
        (ex-info "jolt.sim.runtime requires a sim-enabled Jolt image"
                 {:type :jolt.sim.runtime/abi-unavailable}))

      (seq base-missing)
      (throw
        (ex-info "jolt.internal.sim exposes an incomplete controller ABI"
                 {:type :jolt.sim.runtime/abi-incompatible
                  :missing base-missing})))
    (let [caps-fn @(:capabilities vars)
          caps-value
          (try
            (caps-fn)
            (catch :default error
              (throw
                (ex-info
                  "jolt.internal.sim/capabilities failed"
                  {:type :jolt.sim.runtime/abi-incompatible
                   :error (trace/normalize-error error)}))))
          descriptor (validate-descriptor caps-value)]
      (when (= 2 (:abi-version descriptor))
        (let [ffi-missing
              (vec
                (keep (fn [key]
                        (when-not (vars key) key))
                      ffi-abi-keys))]
          (when (seq ffi-missing)
            (throw
              (ex-info
                "jolt.internal.sim exposes a v2 descriptor without the FFI controller ABI"
                {:type :jolt.sim.runtime/abi-incompatible
                 :missing ffi-missing})))))
      {:descriptor descriptor
       :abi-version (:abi-version descriptor)
       :install-controller! @(:install-controller! vars)
       :restore-controller! @(:restore-controller! vars)
       :controller-errors @(:controller-errors vars)
       :clear-controller-errors! @(:clear-controller-errors! vars)
       :install-ffi-controller! (some-> (:install-ffi-controller! vars) deref)
       :restore-ffi-controller! (some-> (:restore-ffi-controller! vars) deref)})))

(defn capabilities
  "Resolves and returns the validated controller capability descriptor from a
  sim image (ABI v1 or v2). This is the validated descriptor only — the raw
  controller functions are never exposed publicly.

  Throws typed ex-info tagged :jolt.sim.runtime/abi-unavailable on an ordinary
  image, and :jolt.sim.runtime/abi-incompatible when the image does not expose
  an exact v1 or v2 descriptor and operation set."
  []
  (:descriptor (resolve-controller-ops!)))

(defn- invalid-controller-event! [reason record]
  (throw
   (ex-info
    "jolt.internal.sim emitted an invalid controller event"
    {:type :jolt.sim.runtime/invalid-controller-event
     :reason reason
     :event record})))

(defn- validate-controller-event! [{:keys [event task parent] :as record}]
  (when-not (contains? supported-events event)
    (invalid-controller-event! :unknown-event record))
  (when-not (and (integer? task) (pos? task))
    (invalid-controller-event! :invalid-task-id record))
  (when-not (and (integer? parent) (not (neg? parent)))
    (invalid-controller-event! :invalid-parent-id record))
  record)

(defn- apply-event [state {:keys [event task] :as record}]
  (let [seen (:seen state)
        active (:active state)
        next-state
        (case event
          :spawn
          (if (contains? seen task)
            (invalid-controller-event! :duplicate-spawn record)
            (-> state
                (update :seen conj task)
                (update :active conj task)))

          :start
          (if (contains? seen task)
            state
            (invalid-controller-event! :start-before-spawn record))

          (:finish :cancel)
          (if (contains? active task)
            (update state :active disj task)
            (invalid-controller-event! :terminal-without-active-task record)))]
    (-> next-state
        (update :events conj record)
        (update :in-flight conj [event task]))))

(defn- begin-event!
  "Atomically orders an event against scope closure. A late :spawn is rejected
  before its worker can fork, and a late :start is rejected before its body can
  run. Late terminal events return false so an already-reported outliving task
  can still publish its result or cancellation and clean up."
  [state record]
  (loop []
    (let [before @state]
      (if (:closed? before)
        (case (:event record)
          (:spawn :start)
          (invalid-controller-event! :event-after-scope record)

          false)
        (let [after (apply-event before record)]
          (if (compare-and-set! state before after)
            true
            (recur)))))))

(defn- end-event! [state event task]
  (swap! state update :in-flight disj [event task]))

(defn- record-controller-error! [state record error]
  (swap! state update :local-errors conj
         (assoc record :error error)))

(defn- make-controller
  "Returns a controller fn invoked positionally as (event id parent) by the
  Jolt future lifecycle hook. It records each event as an ordered exact
  {:event :task :parent} map, tracks a task's lifetime from :spawn through its
  terminal :finish/:cancel, and forwards every event map to on-event on the
  hook thread. on-event may block during :start to gate that future. Throwing
  from any callback is a controller failure; :finish/:cancel failures are
  latched by the runtime rather than replacing the application outcome."
  [on-event state]
  (fn controller [event id parent]
    (let [record {:event event :task id :parent parent}
          error-recorded? (volatile! false)]
      (try
        (validate-controller-event! record)
        (when (begin-event! state record)
          (try
            (when (some? on-event)
              (on-event record))
            (catch :default error
              ;; Record before clearing :in-flight. This closes the otherwise
              ;; possible race where the body observes neither an in-flight
              ;; callback nor the supervisor latch while Scheme is still
              ;; unwinding the callback failure.
              (record-controller-error! state record error)
              (vreset! error-recorded? true)
              (throw error))
            (finally
              (end-event! state event id))))
        nil
        (catch :default error
          (when-not @error-recorded?
            (record-controller-error! state record error))
          (throw error))))))

;; ---- FFI interception (ABI v2) ----------------------------------------

(defn- invalid-ffi-descriptor! [reason descriptor]
  (throw
   (ex-info
    "The sim image emitted a malformed FFI interception descriptor"
    {:type :jolt.sim.runtime/invalid-ffi-descriptor
     :reason reason
     :descriptor descriptor})))

(def ^:private foreign-function-keys
  #{:kind :task :arguments :symbol :argument-types :return-type :blocking?})

(def ^:private native-operation-keys
  #{:kind :task :arguments :operation})

(defn- validate-ffi-descriptor! [descriptor]
  (when-not (map? descriptor)
    (invalid-ffi-descriptor! :not-a-map descriptor))
  (let [{:keys [kind task arguments]} descriptor]
    (when-not (contains? ffi-kinds kind)
      (invalid-ffi-descriptor! :unknown-kind descriptor))
    (when-not (and (integer? task) (not (neg? task)))
      (invalid-ffi-descriptor! :invalid-task descriptor))
    (when-not (vector? arguments)
      (invalid-ffi-descriptor! :invalid-arguments descriptor))
    (case kind
      :foreign-function
      (do
        (when-not (= foreign-function-keys (set (keys descriptor)))
          (invalid-ffi-descriptor! :foreign-function-key-mismatch descriptor))
        (when-not (string? (:symbol descriptor))
          (invalid-ffi-descriptor! :invalid-symbol descriptor))
        (when-not (and (vector? (:argument-types descriptor))
                       (every? keyword? (:argument-types descriptor)))
          (invalid-ffi-descriptor! :invalid-argument-types descriptor))
        (when-not (keyword? (:return-type descriptor))
          (invalid-ffi-descriptor! :invalid-return-type descriptor))
        (when-not (boolean? (:blocking? descriptor))
          (invalid-ffi-descriptor! :invalid-blocking descriptor)))
      :native-operation
      (do
        (when-not (= native-operation-keys (set (keys descriptor)))
          (invalid-ffi-descriptor! :native-operation-key-mismatch descriptor))
        (when-not (contains? native-operations (:operation descriptor))
          (invalid-ffi-descriptor! :unknown-operation descriptor))))
    descriptor))

(defn- descriptor-handler-key [descriptor]
  (case (:kind descriptor)
    :native-operation
    [:native-operation (:operation descriptor)]
    :foreign-function
    [:foreign-function (:symbol descriptor) (:argument-types descriptor)
     (:return-type descriptor) (:blocking? descriptor)]))

(defn- record-arrival! [effects-log descriptor]
  "Atomically appends the validated descriptor in interception-arrival order.
  Handler completion order cannot reorder this evidence."
  (swap! effects-log conj descriptor)
  nil)

(defn- record-ffi-error! [state descriptor category error]
  (swap! state update :ffi-errors conj
         (cond-> {:ffi-error category :descriptor descriptor}
           (some? error) (assoc :error error))))

(defn- make-ffi-controller
  "Returns the FFI controller fn installed under ABI v2. It validates every
  incoming descriptor exactly, records it in arrival order before lookup, and
  dispatches to the registered handler (nil handler is a valid substitution
  returning nil). An unhandled effect throws
  :jolt.sim.runtime/unhandled-native-effect before any OS access.
  Handler, missing-handler, and malformed-descriptor failures are latched in
  state so application code cannot catch the thrown exception and make the run
  succeed."
  [handlers state effects-log]
  (fn ffi-controller [descriptor]
    (let [latched? (volatile! false)
          validated? (volatile! false)]
      (try
        (validate-ffi-descriptor! descriptor)
        (vreset! validated? true)
        (record-arrival! effects-log descriptor)
        (let [key (descriptor-handler-key descriptor)
              entry (find handlers key)]
          (if entry
            (let [handler-fn (val entry)
                  result (if (some? handler-fn)
                           (handler-fn descriptor)
                           nil)]
              result)
            (do
              (record-ffi-error! state descriptor :unhandled-native-effect nil)
              (vreset! latched? true)
              (throw
                (ex-info
                  "A native effect was intercepted without a registered handler"
                  {:type :jolt.sim.runtime/unhandled-native-effect
                   :handler-key key
                   :descriptor descriptor})))))
        (catch :default error
          (when-not @latched?
            (let [category (if @validated? :handler-error :invalid-descriptor)]
              (record-ffi-error! state descriptor category error))
            (vreset! latched? true))
          (throw error))))))

;; ---- config / session / run -------------------------------------------

(defn- close-state! [state]
  (swap! state assoc :closed? true))

(defn- outliving-tasks [snapshot]
  (vec
   (sort
    (set
     (concat (:active snapshot)
             (map second (:in-flight snapshot)))))))

(defn- controller-errors [snapshot latched]
  (let [local (trace/normalize-error (:local-errors snapshot))
        ffi (trace/normalize-error (:ffi-errors snapshot))
        local-identities
        (set (map (juxt :event :task :parent) local))
        supervisor (trace/normalize-error latched)]
    (vec
     (concat
      local
      ffi
      ;; Chez returns the same callback failure through its supervisor latch,
      ;; but the condition may project as an opaque host object after crossing
      ;; back into Jolt. Prefer the locally normalized Throwable for an event
      ;; identity we already captured; retain any supervisor-only evidence.
      (remove
       #(contains? local-identities
                   ((juxt :event :task :parent) %))
        supervisor)))))

(defn- validate-handler-key! [key]
  (cond
    (and (vector? key)
         (= 2 (count key))
         (= :native-operation (nth key 0))
         (contains? native-operations (nth key 1)))
    nil

    (and (vector? key)
         (= 5 (count key))
         (= :foreign-function (nth key 0))
         (string? (nth key 1))
         (vector? (nth key 2))
         (every? keyword? (nth key 2))
         (keyword? (nth key 3))
         (boolean? (nth key 4)))
    nil

    :else
    (throw
     (ex-info
      "run-controlled :ffi-handlers entry has a malformed key"
      {:type :jolt.sim.runtime/invalid-config
       :handler-key key}))))

(defn- validate-ffi-handlers! [handlers]
  (when-not (map? handlers)
    (throw
     (ex-info "run-controlled :ffi-handlers must be a map"
              {:type :jolt.sim.runtime/invalid-config})))
  (doseq [[key value] handlers]
    (validate-handler-key! key)
    (when-not (or (nil? value) (fn? value))
      (throw
       (ex-info
        "run-controlled :ffi-handlers entry must be nil or a function"
        {:type :jolt.sim.runtime/invalid-config
         :handler-key key}))))
  handlers)

(defn- restore-controllers!
  "Restores both installed controllers in reverse order, attempting the future
  restore even when the FFI restore fails. The session becomes reusable only
  after every exact-token restore succeeds."
  [ops ffi-token future-token]
  (let [failures (atom [])]
    (when (some? ffi-token)
      (try
        ((:restore-ffi-controller! ops) ffi-token)
        (catch :default error
          (swap! failures conj {:controller :ffi :error error}))))
    (when (some? future-token)
      (try
        ((:restore-controller! ops) future-token)
        (catch :default error
          (swap! failures conj {:controller :future :error error}))))
    (if (seq @failures)
      (do
        (reset! session-state :poisoned)
        (throw
         (ex-info
          "jolt.sim.runtime could not restore its controller ownership"
          {:type :jolt.sim.runtime/controller-cleanup-error
           :errors (trace/normalize-error @failures)})))
      (reset! session-state :idle))))

(defn- validate-run-arguments! [config thunk]
  (when-not (map? config)
    (throw
     (ex-info "run-controlled config must be a map"
              {:type :jolt.sim.runtime/invalid-config})))
  ;; :on-event is always available; :ffi-handlers is structurally validated
  ;; here so malformed handler config is reported before ABI resolution, but its
  ;; availability is gated by ABI version after resolution.
  (let [unknown-keys
        (vec (sort (remove #{:on-event :ffi-handlers} (keys config))))]
    (when (seq unknown-keys)
      (throw
       (ex-info
        "run-controlled config contains unsupported options"
        {:type :jolt.sim.runtime/invalid-config
         :unknown-keys unknown-keys}))))
  (when-not (fn? thunk)
    (throw
     (ex-info "run-controlled thunk must be a function"
              {:type :jolt.sim.runtime/invalid-config})))
  (let [on-event (:on-event config)]
    (when-not (or (nil? on-event) (fn? on-event))
      (throw
       (ex-info "run-controlled :on-event must be a function"
                {:type :jolt.sim.runtime/invalid-config}))))
  (when (contains? config :ffi-handlers)
    (validate-ffi-handlers! (:ffi-handlers config))))

(defn run-controlled
  "Runs thunk under the simulation controller described by config.

  Resolves the controller ABI (v1 or v2), throwing
  :jolt.sim.runtime/abi-unavailable or /abi-incompatible otherwise. Under v1 an
  explicitly supplied :ffi-handlers key is rejected with
  :jolt.sim.runtime/capability-unavailable. Atomically claims the single
  session so overlapping or nested runs fail closed with
  :jolt.sim.runtime/session-overlap, clears stale controller errors, then
  installs the future lifecycle controller (and, under v2, the FFI controller
  afterwards) before running the unchanged thunk. Restoration is the reverse:
  FFI then future, both attempted on cleanup. The original body failure is
  preserved whenever cleanup succeeds.

  The future controller records an ordered event log of exact
  {:event :task :parent} maps and forwards each to the optional (:on-event
  config) on the hook thread (blocking at :start gates that task). Under v2 the
  FFI controller records an ordered :effects log of every validated native
  interception in arrival order; an unhandled effect throws
  :jolt.sim.runtime/unhandled-native-effect before OS access, and handler or
  missing-handler failures are latched locally so application code cannot catch
  them and make the run succeed.

  After the body returns, fails closed with :jolt.sim.runtime/controller-error
  if a callback or handler failed (including a supervisor-latched terminal
  failure, normalized through jolt.sim.trace/normalize-error), and with
  :jolt.sim.runtime/tasks-outlive-scope if any task reached :spawn inside the
  scope but did not reach :finish or :cancel before the body returned.

  On success returns {:result value :events vector-of-maps :capabilities
  descriptor}. Under v2 the map also includes :effects, a vector of
  exact validated descriptors in interception-arrival order; their arguments
  are live in-memory evidence and may contain mutable objects such as byte
  arrays. Under v1 :effects is omitted."
  [config thunk]
  (validate-run-arguments! config thunk)
  (let [ops (resolve-controller-ops!)
        abi-version (:abi-version ops)
        on-event (:on-event config)]
    (when (and (= abi-version 1) (contains? config :ffi-handlers))
      (throw
       (ex-info
        "run-controlled :ffi-handlers requires an ABI v2 sim image"
        {:type :jolt.sim.runtime/capability-unavailable})))
    (let [ffi-handlers (or (:ffi-handlers config) {})
          state
          (atom {:events []
                 :seen #{}
                 :active #{}
                 :in-flight #{}
                 :local-errors []
                 :ffi-errors []
                 :closed? false})
          effects-log (atom [])
          controller (make-controller on-event state)
          ffi-controller (when (= abi-version 2)
                           (make-ffi-controller
                            ffi-handlers state effects-log))]
      (when-not (compare-and-set! session-state :idle :active)
        (let [status @session-state]
          (throw
           (ex-info
            (if (= status :poisoned)
              "jolt.sim.runtime controller ownership is poisoned"
              "jolt.sim.runtime sessions cannot overlap or nest")
            {:type (if (= status :poisoned)
                     :jolt.sim.runtime/session-poisoned
                     :jolt.sim.runtime/session-overlap)}))))
      (let [future-token (volatile! nil)
            ffi-token (volatile! nil)]
        (try
          ((:clear-controller-errors! ops))
          ;; Token capture happens before the next installation so a partial
          ;; future-then-FFI setup can still restore the future controller.
          (vreset! future-token
                   ((:install-controller! ops) controller))
          (when ffi-controller
            (vreset! ffi-token
                     ((:install-ffi-controller! ops) ffi-controller)))
          (let [result (thunk)
                snapshot (close-state! state)
                latched ((:controller-errors ops))
                errors (controller-errors snapshot latched)
                outliving (outliving-tasks snapshot)]
            (cond
              (seq errors)
              (throw
               (ex-info
                "A controller or native-effect callback failed"
                (cond-> {:type :jolt.sim.runtime/controller-error
                         :errors errors}
                  (= abi-version 2) (assoc :effects @effects-log))))

              (seq outliving)
              (throw
               (ex-info
                "Tasks spawned inside run-controlled outlived its scope"
                {:type :jolt.sim.runtime/tasks-outlive-scope
                 :tasks outliving}))

              :else
              (let [base {:result result
                          :events (:events snapshot)
                          :capabilities (:descriptor ops)}]
                (if (= abi-version 2)
                  (assoc base :effects @effects-log)
                  base))))
          (finally
            ;; Closing before restore rejects late lifecycle starts. Restoration
            ;; is separately fail-closed and always attempts both token owners.
            (try
              (close-state! state)
              (finally
                (restore-controllers!
                 ops @ffi-token @future-token)))))))))

(defmacro defsim
  "Defines a no-arg scenario named name that runs body under run-controlled with
  config. The body is ordinary Jolt code; defsim only installs the controller,
  event capture, and cleanup around it. The expansion references the fully
  qualified run-controlled, so the scenario is callable from any namespace. Not
  coupled to clojure.test."
  [name config & body]
  `(defn ~name []
     (jolt.sim.runtime/run-controlled ~config (fn [] ~@body))))
