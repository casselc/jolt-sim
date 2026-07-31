(ns jolt.sim.runtime
  "Test-only adapter that bridges ordinary Jolt code to the optional simulation
  controller exposed by a sim-enabled Jolt image.

  Core Jolt exposes sim-image-only vars in the namespace jolt.internal.sim:

    capabilities             ;; () -> {:abi-version 1 :future-lifecycle true
                              ;;        :controller-errors true
                              ;;        :events [:spawn :start :finish :cancel]}
    install-controller!      ;; (controller) -> opaque token
    restore-controller!      ;; (token) -> nil, restores exactly what that
                              ;; token displaced; only the current top of the
                              ;; strict-LIFO installation stack may restore
    controller-errors        ;; () -> latched controller hook errors
    clear-controller-errors! ;; () -> nil, clears latched errors

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

  Scope of v1. run-controlled observes task starts and lets :on-event block at
  :start to gate them. It records an ordered event log of exact
  {:event :task :parent} maps, admits one exclusive run at a time, and fails
  closed on controller errors and on tasks that outlive the controlled scope.
  It is NOT yet an exhaustive deterministic scheduler: it does not reorder
  execution, advance virtual time, inject faults, or intercept native effects.
  Those remain future work tracked in the project README."
  (:require [jolt.sim.trace :as trace]))

(def ^:private expected-descriptor
  {:abi-version 1
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel]})

(def ^:private supported-events
  (set (:events expected-descriptor)))

(def ^:private capabilities-sym 'jolt.internal.sim/capabilities)
(def ^:private install-sym 'jolt.internal.sim/install-controller!)
(def ^:private restore-sym 'jolt.internal.sim/restore-controller!)
(def ^:private errors-sym 'jolt.internal.sim/controller-errors)
(def ^:private clear-errors-sym 'jolt.internal.sim/clear-controller-errors!)

;; Single active run-controlled session. Compare-and-set! claims it atomically,
;; so overlapping or nested runs fail closed without a separate lock.
(def ^:private session-active? (atom false))

(defn- safe-resolve [sym]
  (try (resolve sym) (catch :default _ nil)))

(defn- resolved-abi-vars
  "Returns all five controller ABI resolution results, including nils."
  []
  {:capabilities (safe-resolve capabilities-sym)
   :install-controller! (safe-resolve install-sym)
   :restore-controller! (safe-resolve restore-sym)
   :controller-errors (safe-resolve errors-sym)
   :clear-controller-errors! (safe-resolve clear-errors-sym)})

(defn available?
  "True only on a sim-enabled Jolt image that exposes the full controller ABI.
  Returns false on an ordinary released image without throwing."
  []
  (every? some? (vals (resolved-abi-vars))))

(defn- validate-descriptor [caps-value]
  (when-not (map? caps-value)
    (throw
     (ex-info "jolt.internal.sim/capabilities did not return a map"
              {:type :jolt.sim.runtime/abi-incompatible
               :capabilities-class (str (class caps-value))})))
  (when-not (= expected-descriptor caps-value)
    (throw
     (ex-info "jolt.internal.sim exposes an incompatible controller ABI"
              {:type :jolt.sim.runtime/abi-incompatible
               :expected expected-descriptor
               :actual caps-value})))
  caps-value)

(defn- resolve-controller-ops!
  "Resolves the five controller ABI vars and the validated capabilities
  descriptor, or throws :jolt.sim.runtime/abi-unavailable (no sim image) or
  :jolt.sim.runtime/abi-incompatible (unsupported/malformed descriptor)."
  []
  (let [vars (resolved-abi-vars)
        missing
        (vec
         (keep (fn [[operation resolved]]
                 (when-not resolved operation))
               vars))]
    (cond
      (= (count missing) (count vars))
      (throw
       (ex-info "jolt.sim.runtime requires a sim-enabled Jolt image"
                {:type :jolt.sim.runtime/abi-unavailable}))

      (seq missing)
      (throw
       (ex-info "jolt.internal.sim exposes an incomplete controller ABI"
                {:type :jolt.sim.runtime/abi-incompatible
                 :missing missing})))
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
      {:descriptor descriptor
       :install-controller! @(:install-controller! vars)
       :restore-controller! @(:restore-controller! vars)
       :controller-errors @(:controller-errors vars)
       :clear-controller-errors! @(:clear-controller-errors! vars)})))

(defn capabilities
  "Resolves and returns the validated v1 controller capability descriptor from
  a sim image: {:abi-version 1 :future-lifecycle true :controller-errors true
  :events [:spawn :start :finish :cancel]}. This is the validated descriptor
  only — the raw controller functions are never exposed publicly.

  Throws typed ex-info tagged :jolt.sim.runtime/abi-unavailable on an ordinary
  image, and :jolt.sim.runtime/abi-incompatible when the image does not expose
  the exact v1 descriptor and operation set."
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
        local-identities
        (set (map (juxt :event :task :parent) local))
        supervisor (trace/normalize-error latched)]
    (vec
     (concat
      local
      ;; Chez returns the same callback failure through its supervisor latch,
      ;; but the condition may project as an opaque host object after crossing
      ;; back into Jolt. Prefer the locally normalized Throwable for an event
      ;; identity we already captured; retain any supervisor-only evidence.
      (remove
       #(contains? local-identities
                   ((juxt :event :task :parent) %))
       supervisor)))))

(defn- validate-run-arguments! [config thunk]
  (when-not (map? config)
    (throw
     (ex-info "run-controlled config must be a map"
              {:type :jolt.sim.runtime/invalid-config})))
  (let [unknown-keys
        (vec (sort (remove #{:on-event} (keys config))))]
    (when (seq unknown-keys)
      (throw
       (ex-info
        "run-controlled config contains unsupported v1 options"
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
                {:type :jolt.sim.runtime/invalid-config})))))

(defn run-controlled
  "Runs thunk under the v1 simulation controller described by config.

  Validates ABI v1 (throwing :jolt.sim.runtime/abi-unavailable or
  /abi-incompatible otherwise), atomically claims the single session so that
  overlapping or nested runs fail closed with :jolt.sim.runtime/session-overlap,
  clears stale controller errors, installs a wrapper controller that records
  an ordered event log of exact {:event :task :parent} maps and forwards each
  one to the optional (:on-event config) on the hook thread (blocking at
  :start gates that task), then runs the unchanged thunk. Throwing from a
  callback is a controller error. The controller is always restored with the
  exact opaque token install-controller! returned, and the session released,
  in a finally block, even when the body or a hook throws.

  After the body returns, fails closed with :jolt.sim.runtime/controller-error
  if a callback failed (including a supervisor-latched terminal failure,
  normalized through jolt.sim.trace/normalize-error), and with
  :jolt.sim.runtime/tasks-outlive-scope if any task reached :spawn inside the
  scope but did not reach :finish or :cancel before the body returned.

  On success returns {:result value :events vector-of-maps :capabilities
  descriptor}. This v1 observes and gates future starts; it is not exhaustive
  deterministic scheduling."
  [config thunk]
  (validate-run-arguments! config thunk)
  (let [ops (resolve-controller-ops!)
        on-event (:on-event config)
        state
        (atom {:events []
               :seen #{}
               :active #{}
               :in-flight #{}
               :local-errors []
               :closed? false})
        controller (make-controller on-event state)]
    (when-not (compare-and-set! session-active? false true)
      (throw
       (ex-info "jolt.sim.runtime sessions cannot overlap or nest"
                {:type :jolt.sim.runtime/session-overlap})))
    (try
      ((:clear-controller-errors! ops))
      (let [token ((:install-controller! ops) controller)]
        (try
          (let [result (thunk)
                snapshot (close-state! state)
                latched ((:controller-errors ops))
                errors (controller-errors snapshot latched)
                outliving (outliving-tasks snapshot)]
            (cond
              (seq errors)
              (throw
               (ex-info
                "A controller hook failed during the controlled run"
                {:type :jolt.sim.runtime/controller-error
                 :errors errors}))

              (seq outliving)
              (throw
               (ex-info
                "Tasks spawned inside run-controlled outlived its scope"
                {:type :jolt.sim.runtime/tasks-outlive-scope
                 :tasks outliving}))

              :else
              {:result result
               :events (:events snapshot)
               :capabilities (:descriptor ops)}))
          (finally
            ;; The success path has already closed the snapshot. Repeating the
            ;; idempotent close here also covers errors before that binding;
            ;; the nested finally guarantees exact-token restoration.
            (try
              (close-state! state)
              (finally
                ((:restore-controller! ops) token))))))
      (finally
        (reset! session-active? false)))))

(defmacro defsim
  "Defines a no-arg scenario named name that runs body under run-controlled with
  config. The body is ordinary Jolt code; defsim only installs the controller,
  event capture, and cleanup around it. The expansion references the fully
  qualified run-controlled, so the scenario is callable from any namespace. Not
  coupled to clojure.test."
  [name config & body]
  `(defn ~name []
     (jolt.sim.runtime/run-controlled ~config (fn [] ~@body))))
