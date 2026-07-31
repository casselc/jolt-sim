(ns jolt.sim.runtime
  "Test-only adapter that bridges ordinary Jolt code to the optional simulation
  controller exposed by a sim-enabled Jolt image.

  Core Jolt exposes sim-image-only vars in the namespace jolt.internal.sim:

    capabilities             ;; () -> descriptor (ABI v1/v2/v3, see below)
    install-controller!      ;; (controller) -> opaque token
    restore-controller!      ;; (token) -> nil, restores exactly what that
                              ;; token displaced; only the current top of the
                              ;; strict-LIFO installation stack may restore
    controller-errors        ;; () -> latched controller hook errors
    clear-controller-errors! ;; () -> nil, clears latched errors

  ABI v2/v3 additionally expose:

    install-ffi-controller!  ;; (ffi-controller) -> opaque token
    restore-ffi-controller!  ;; (token) -> nil, LIFO-restores the FFI controller

  The controller fn install-controller! installs is invoked positionally as
  (controller event id parent) on the future lifecycle hook thread — event is
  one of :spawn/:start/:finish/:cancel and, under v3, :exit/:abort. id is that
  task's stable id; parent is the task id in effect on the spawning thread.
  v1/v2 terminal ownership ends at :finish/:cancel. v3 separately retains
  worker ownership through :exit, or balances a pre-fork failure with :abort.
  A :spawn hook failure propagates before the worker forks, while a :start hook
  failure becomes that future's own failure. Terminal hook failures never
  replace the application result; they are latched in controller-errors.

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

  ABI v3 is the v2 FFI descriptor plus two worker-ownership events. The
  controller is still invoked as (event id parent), but event may also be
  :exit/:abort. A task owns a worker from :spawn until exactly one
  :exit/:abort; :finish/:cancel settle its future but do NOT release that
  ownership, so a cancelled running worker remains owned until :exit. This
  lets cleanup wait for real worker release rather than guessing from future
  settlement.

    {:abi-version 3 :future-lifecycle true :controller-errors true
     :events [:spawn :start :finish :cancel :exit :abort]
     :ffi-interception {:descriptor-version 1
                        :kinds [:foreign-function :native-operation]
                        :arguments :live
                        :task-identity :future-lifecycle
                        :native-operations [:load-library :loaded? :alloc :free
                                            :read :write :sizeof :read-bytes
                                            :write-bytes :read-array :write-array
                                            :ptr->string :string->ptr]}}

  ABI v3 may additionally expose :ffi-interception :descriptor-version 2 in
  place of 1. Under descriptor-version 2 every :foreign-function descriptor
  carries one additional Boolean key, :capture-native-error?; native-operation
  descriptors are unchanged. v1, v2 (always descriptor-version 1), and v3 with
  descriptor-version 1 remain exact as documented above.

  ABI v3 may additionally expose :ffi-interception :descriptor-version 3 in
  place of 1 or 2. Descriptor-version 3 keeps every descriptor-version 2
  :foreign-function key set and Boolean :capture-native-error? semantics, keeps
  the :native-operation descriptor shape unchanged, and extends the ordered
  :native-operations list from 13 to 15 entries by inserting
  :borrow-byte-array and :release-byte-array after :write-array and before
  :ptr->string. v1, v2, v3 with descriptor-version 1, and v3 with
  descriptor-version 2 remain exact as documented above; v3 with
  descriptor-version 3 is accepted only with that exact 15-entry
  :native-operations list.

  Under v2/v3, run-controlled installs the FFI controller on every run even when
  no handlers are configured. A native effect inside the scope that has no
  registered handler throws :jolt.sim.runtime/unhandled-native-effect before
  the OS is reached. Handlers are configured via :ffi-handlers, a map keyed by
  [:native-operation operation] or, for foreign functions, the canonical
  six-element [:foreign-function symbol argument-types return-type blocking?
  capture?]. The legacy five-element key (without capture?) is also accepted
  and canonicalizes to capture? false; supplying both the legacy key and its
  equivalent six-element-false key for the same signature is rejected as an
  ambiguous config rather than silently overwritten. Map membership (under the
  canonical key) defines handled, so a nil value is a valid substitute. When
  capture? is true, the registered handler's returned public value must be a
  vector of exactly two elements; a malformed shape throws
  :jolt.sim.runtime/invalid-capture-result. Handler, missing-handler, and
  malformed-capture-result failures are latched locally so application code
  cannot catch them and make the run succeed.

  run-controlled observes task starts and lets :on-event block at :start to
  gate them. It records an ordered event log of exact {:event :task :parent}
  maps, admits one exclusive run at a time, and fails closed on controller
  errors and on tasks that outlive the controlled scope. Under v2/v3 it also
  records an ordered :effects log of every validated native interception in
  arrival order. Under v3 it drains lifecycle-owned future workers through
  :exit/:abort before restoring controllers. Under v3 an optional
  :future-schedule additionally drives the first coarse deterministic
  scheduler (jolt.sim.future-schedule) over unchanged ordinary futures spawned
  directly by the thunk, admitting one scripted ordinal's body at a time. It
  is NOT yet an exhaustive deterministic scheduler: it does not search
  schedules, support nested spawns or cancellation, advance virtual time,
  inject faults, or bind a unified causal trace between lifecycle events and
  native effects. Those remain future work tracked in the project README."
  (:require [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.trace :as trace]))

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

;; ABI v3 is the v2 FFI descriptor plus two worker-ownership events. A task
;; owns a worker from :spawn until exactly one :exit/:abort; :finish/:cancel
;; settle its future but do NOT release that ownership, so a cancelled running
;; worker remains :unexited until :exit.
(def ^:private v3-descriptor
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

;; ABI v3 with :ffi-interception :descriptor-version 2 is otherwise identical
;; to v3-descriptor; only the nested FFI descriptor version changes, which
;; adds :capture-native-error? to every :foreign-function descriptor.
(def ^:private v3-descriptor2
  (assoc v3-descriptor
         :ffi-interception
         (assoc (:ffi-interception v3-descriptor) :descriptor-version 2)))

;; ABI v3 with :ffi-interception :descriptor-version 3 extends the ordered
;; :native-operations list with :borrow-byte-array and :release-byte-array
;; (inserted after :write-array, before :ptr->string) while keeping the
;; descriptor-version 2 foreign-function key set and :capture-native-error?
;; semantics and the native-operation descriptor shape. Built on
;; v3-descriptor2 so only the two changed nested fields are stated.
(def ^:private v3-descriptor3
  (-> v3-descriptor2
      (assoc-in [:ffi-interception :descriptor-version] 3)
      (assoc-in [:ffi-interception :native-operations]
                [:load-library :loaded? :alloc :free
                 :read :write :sizeof :read-bytes
                 :write-bytes :read-array :write-array
                 :borrow-byte-array :release-byte-array
                 :ptr->string :string->ptr])))

(def ^:private v1-events
  (set (:events v1-descriptor)))

(def ^:private v3-events
  (set (:events v3-descriptor)))

(def ^:private drain-events
  "Terminal events accepted (and applied) during scope closing for drainage.
  Under v1/v2 late terminal events are observed but not applied; under v3 they
  are applied so :exit/:abort can balance outstanding worker ownership."
  #{:finish :cancel :exit :abort})

(def ^:private native-operations
  (set (get-in v2-descriptor [:ffi-interception :native-operations])))

;; Descriptor-version 3 advertises the base operation list plus the two
;; pointer-loan operations :borrow-byte-array and :release-byte-array.
(def ^:private native-operations-v3
  (set (get-in v3-descriptor3 [:ffi-interception :native-operations])))

;; Handler config validation is pure config checking that runs before ABI
;; resolution, so it recognizes every operation any exact capability
;; descriptor may advertise. The descriptor-version 3 list is a superset of
;; the base list, so the union reduces to it.
(def ^:private config-native-operations
  (into native-operations native-operations-v3))

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
  ABI v2/v3."
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
  without throwing. The FFI vars are validated separately for v2/v3."
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
                (= v2-descriptor caps-value)
                (= v3-descriptor caps-value)
                (= v3-descriptor2 caps-value)
                (= v3-descriptor3 caps-value))
    (throw
     (ex-info "jolt.internal.sim exposes an incompatible controller ABI"
              {:type :jolt.sim.runtime/abi-incompatible
               :expected [v1-descriptor v2-descriptor v3-descriptor
                          v3-descriptor2 v3-descriptor3]
               :actual caps-value})))
  caps-value)

(defn- resolve-controller-ops!
  "Resolves the controller ABI vars and the validated capabilities descriptor,
  or throws :jolt.sim.runtime/abi-unavailable (no sim image) or
  :jolt.sim.runtime/abi-incompatible (unsupported/malformed descriptor or a
  v2/v3 descriptor whose FFI vars are missing)."
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
      (when (#{2 3} (:abi-version descriptor))
        (let [ffi-missing
              (vec
                (keep (fn [key]
                        (when-not (vars key) key))
                      ffi-abi-keys))]
          (when (seq ffi-missing)
            (throw
              (ex-info
                "jolt.internal.sim exposes an FFI-capable descriptor without the FFI controller ABI"
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
  sim image (ABI v1, v2, or v3). This is the validated descriptor only — the raw
  controller functions are never exposed publicly.

  Throws typed ex-info tagged :jolt.sim.runtime/abi-unavailable on an ordinary
  image, and :jolt.sim.runtime/abi-incompatible when the image does not expose
  an exact v1, v2, or v3 descriptor and operation set."
  []
  (:descriptor (resolve-controller-ops!)))

(defn- invalid-controller-event! [reason record]
  (throw
   (ex-info
    "jolt.internal.sim emitted an invalid controller event"
    {:type :jolt.sim.runtime/invalid-controller-event
     :reason reason
     :event record})))

(defn- validate-controller-event! [events-set {:keys [event task parent] :as record}]
  (when-not (contains? events-set event)
    (invalid-controller-event! :unknown-event record))
  (when-not (and (integer? task) (pos? task))
    (invalid-controller-event! :invalid-task-id record))
  (when-not (and (integer? parent) (not (neg? parent)))
    (invalid-controller-event! :invalid-parent-id record))
  record)

(defn- apply-event [abi-version state {:keys [event task] :as record}]
  (let [seen (:seen state)
        active (:active state)
        next-state
        (case event
          :spawn
          (if (contains? seen task)
            (invalid-controller-event! :duplicate-spawn record)
            (cond-> (-> state
                        (update :seen conj task)
                        (update :active conj task))
              (= abi-version 3) (update :unexited conj task)))

          :start
          (cond
            (not (contains? seen task))
            (invalid-controller-event! :start-before-spawn record)

            (and (= abi-version 3)
                 (contains? (:started state) task))
            (invalid-controller-event! :duplicate-start record)

            (= abi-version 3)
            (update state :started conj task)

            :else
            state)

          (:finish :cancel)
          (cond
            (not (contains? active task))
            (invalid-controller-event! :terminal-without-active-task record)
            (= abi-version 3)
            ;; Settle the future; the worker remains :unexited until exactly one
            ;; :exit/:abort releases its ownership.
            (-> state
                (update :active disj task)
                (update :settled conj task))
            :else
            (update state :active disj task))

          ;; :exit/:abort arrive only under ABI v3. A worker may exit only
          ;; after :finish/:cancel settled its future. :abort is the distinct
          ;; pre-worker path and therefore removes both active and unexited
          ;; ownership without requiring settlement.
          :exit
          (cond
            (not (contains? (:unexited state) task))
            (invalid-controller-event! :exit-without-unexited-task record)

            (not (contains? (:settled state) task))
            (invalid-controller-event! :exit-before-terminal record)

            :else
            (-> state
                (update :unexited disj task)
                (update :released conj task)))

          :abort
          (if (and (contains? active task)
                   (contains? (:unexited state) task)
                   (not (contains? (:settled state) task)))
            (-> state
                (update :active disj task)
                (update :unexited disj task)
                (update :released conj task))
            (invalid-controller-event! :abort-without-active-spawn record)))]
    (-> next-state
        (update :events conj record)
        (update :in-flight conj [event task]))))

(defn- reject-event-after-close [state {:keys [event task] :as record}]
  (case event
    :spawn
    (if (contains? (:seen state) task)
      (invalid-controller-event! :duplicate-spawn record)
      (-> state
          (update :seen conj task)
          (update :rejected conj task)
          (update :events conj record)
          (update :in-flight conj [event task])))

    :start
    (-> state
        (update :events conj record)
        (update :in-flight conj [event task]))))

(defn- balance-rejected-spawn [state {:keys [event task] :as record}]
  (-> state
      (update :rejected disj task)
      (update :events conj record)
      (update :in-flight conj [event task])))

(defn- begin-event!
  "Atomically orders an event against scope closure. A late :spawn is rejected
  before its worker can fork, and a late :start is rejected before its body can
  run. Under v1/v2 a late terminal event is observed but not applied (returns
  :ignore). Under v3, late drainage events are applied and still invoke
  on-event. A rejected late :spawn is recorded before failing so the core's
  synchronous balancing :abort can be accepted without inventing worker
  ownership; that special :abort is recorded but not forwarded to on-event."
  [abi-version state record]
  (loop []
    (let [before @state]
      (if (:closed? before)
        (let [event (:event record)]
          (cond
            (and (= abi-version 3) (#{:spawn :start} event))
            (let [after (reject-event-after-close before record)]
              (if (compare-and-set! state before after)
                :reject
                (recur)))

            (#{:spawn :start} event)
            (invalid-controller-event! :event-after-scope record)

            (and (= abi-version 3)
                 (= :abort event)
                 (contains? (:rejected before) (:task record)))
            (let [after (balance-rejected-spawn before record)]
              (if (compare-and-set! state before after)
                :balance
                (recur)))

            (and (= abi-version 3) (contains? drain-events event))
            (let [after (apply-event abi-version before record)]
              (if (compare-and-set! state before after)
                :invoke
                (recur)))

            :else
            :ignore))
        (let [after (apply-event abi-version before record)]
          (if (compare-and-set! state before after)
            :invoke
            (recur)))))))

(defn- end-event! [state event task]
  (swap! state update :in-flight disj [event task]))

(defn- record-controller-error! [state record error]
  (swap! state update :local-errors conj
         (assoc record :error error)))

(defn- make-controller
  "Returns a controller fn invoked positionally as (event id parent) by the
  Jolt future lifecycle hook. It records each event as an ordered exact
  {:event :task :parent} map, tracks a task's lifetime, and forwards every
  accepted event map to on-event on the hook thread. on-event may block during
  :start to gate that future. Throwing from any callback is a
  controller failure; the failure is latched in state before the in-flight
  entry is cleared (so the supervisor never observes a callback neither
  in-flight nor latched), and :finish/:cancel/:exit/:abort failures never
  replace the application result. Under v3 late drainage events still reach
  on-event and remain in-flight until that callback returns."
  [on-event state abi-version]
  (let [events-set (if (= abi-version 3) v3-events v1-events)]
    (fn controller [event id parent]
      (let [record {:event event :task id :parent parent}
            error-recorded? (volatile! false)]
        (try
          (validate-controller-event! events-set record)
          (let [mode (begin-event! abi-version state record)]
            (when-not (= :ignore mode)
              ;; Every mode except :ignore inserted an in-flight entry. Clear
              ;; it only after callback delivery or rejection/error latching.
              ;; :balance is the core's synchronous acknowledgement for a
              ;; rejected late spawn, so no user callback saw that spawn.
              (try
                (case mode
                  :invoke
                  (when (some? on-event)
                    (on-event record))

                  :reject
                  (invalid-controller-event! :event-after-scope record)

                  :balance
                  nil)
                (catch :default error
                  ;; Record before clearing :in-flight. This closes the race
                  ;; where the body observes neither an in-flight callback nor
                  ;; the supervisor latch while Scheme is still unwinding the
                  ;; callback failure.
                  (record-controller-error! state record error)
                  (vreset! error-recorded? true)
                  (throw error))
                (finally
                  (end-event! state event id)))))
          nil
          (catch :default error
            (when-not @error-recorded?
              (record-controller-error! state record error))
            (throw error)))))))

;; ---- FFI interception (ABI v2/v3) -------------------------------------

(defn- invalid-ffi-descriptor! [reason descriptor]
  (throw
   (ex-info
    "The sim image emitted a malformed FFI interception descriptor"
    {:type :jolt.sim.runtime/invalid-ffi-descriptor
     :reason reason
     :descriptor descriptor})))

(def ^:private foreign-function-keys
  #{:kind :task :arguments :symbol :argument-types :return-type :blocking?})

;; Descriptor-version 2 :foreign-function descriptors carry exactly the
;; descriptor-version 1 keys plus :capture-native-error?.
(def ^:private foreign-function-keys-v2
  (conj foreign-function-keys :capture-native-error?))

(def ^:private native-operation-keys
  #{:kind :task :arguments :operation})

(defn- validate-ffi-descriptor!
  "Validates a single intercepted call descriptor exactly against the running
  image's nested FFI descriptor-version (1, 2, or 3). Versions 2 and 3 require
  :foreign-function descriptors to additionally carry a Boolean
  :capture-native-error?; :native-operation descriptors keep the same shape
  across descriptor-versions, but version 3 accepts the two additional
  :borrow-byte-array and :release-byte-array operations."
  [ffi-descriptor-version descriptor]
  (when-not (contains? #{1 2 3} ffi-descriptor-version)
    (invalid-ffi-descriptor! :unsupported-descriptor-version descriptor))
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
        (when-not (= (if (#{2 3} ffi-descriptor-version)
                       foreign-function-keys-v2
                       foreign-function-keys)
                     (set (keys descriptor)))
          (invalid-ffi-descriptor! :foreign-function-key-mismatch descriptor))
        (when-not (string? (:symbol descriptor))
          (invalid-ffi-descriptor! :invalid-symbol descriptor))
        (when-not (and (vector? (:argument-types descriptor))
                       (every? keyword? (:argument-types descriptor)))
          (invalid-ffi-descriptor! :invalid-argument-types descriptor))
        (when-not (= (count (:argument-types descriptor))
                     (count arguments))
          (invalid-ffi-descriptor! :argument-count-mismatch descriptor))
        (when-not (keyword? (:return-type descriptor))
          (invalid-ffi-descriptor! :invalid-return-type descriptor))
        (when-not (boolean? (:blocking? descriptor))
          (invalid-ffi-descriptor! :invalid-blocking descriptor))
        (when (#{2 3} ffi-descriptor-version)
          (when-not (boolean? (:capture-native-error? descriptor))
            (invalid-ffi-descriptor! :invalid-capture-native-error descriptor))))
      :native-operation
      (do
        (when-not (= native-operation-keys (set (keys descriptor)))
          (invalid-ffi-descriptor! :native-operation-key-mismatch descriptor))
        ;; Descriptor-version 3 advertises the two pointer-loan operations in
        ;; addition to the base list; earlier descriptor-versions know only the
        ;; base list, so an out-of-list operation there still fails closed.
        (let [operations (if (= ffi-descriptor-version 3)
                           native-operations-v3
                           native-operations)]
          (when-not (contains? operations (:operation descriptor))
            (invalid-ffi-descriptor! :unknown-operation descriptor)))))
    descriptor))

(defn- descriptor-handler-key
  "Computes the canonical six-element handler identity for a :foreign-function
  descriptor. A descriptor-version 1 descriptor never carries
  :capture-native-error?; its absence is treated as false so v1-style
  descriptors still match a canonical capture?-false handler key."
  [descriptor]
  (case (:kind descriptor)
    :native-operation
    [:native-operation (:operation descriptor)]
    :foreign-function
    [:foreign-function (:symbol descriptor) (:argument-types descriptor)
     (:return-type descriptor) (:blocking? descriptor)
     (get descriptor :capture-native-error? false)]))

(defn- invalid-capture-result! [descriptor result]
  (throw
   (ex-info
    "A captured :foreign-function handler must return a vector of exactly two elements"
    {:type :jolt.sim.runtime/invalid-capture-result
     :descriptor descriptor
     :result result})))

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
  "Returns the FFI controller fn installed under ABI v2/v3. It validates every
  incoming descriptor exactly against ffi-descriptor-version, records it in
  arrival order before lookup, and dispatches to the registered handler (nil
  handler is a valid substitution returning nil). An unhandled effect throws
  :jolt.sim.runtime/unhandled-native-effect before any OS access. When a
  :foreign-function descriptor's :capture-native-error? is true, the handler's
  returned value must be a vector of exactly two elements; a wrong shape
  throws :jolt.sim.runtime/invalid-capture-result. Handler, missing-handler,
  malformed-descriptor, and malformed-capture-result failures are latched in
  state so application code cannot catch the thrown exception and make the run
  succeed."
  [handlers state effects-log ffi-descriptor-version]
  (fn ffi-controller [descriptor]
    (let [latched? (volatile! false)
          validated? (volatile! false)]
      (try
        (validate-ffi-descriptor! ffi-descriptor-version descriptor)
        (vreset! validated? true)
        (record-arrival! effects-log descriptor)
        (let [key (descriptor-handler-key descriptor)
              entry (find handlers key)]
          (if entry
            (let [handler-fn (val entry)
                  result (if (some? handler-fn)
                           (handler-fn descriptor)
                           nil)]
              (when (and (= :foreign-function (:kind descriptor))
                         (true? (:capture-native-error? descriptor))
                         (not (and (vector? result) (= 2 (count result)))))
                (invalid-capture-result! descriptor result))
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

(defn- outliving-tasks [abi-version snapshot]
  ;; Under v3 a task outlives the scope while its worker still owns (has not
  ;; reached :exit/:abort), a rejected spawn has not received its balancing
  ;; :abort, or a lifecycle callback is still executing.
  ;; Under v1/v2 ownership ends at :finish/:cancel, so :active is the test.
  (let [owning
        (if (= abi-version 3)
          (concat (:active snapshot)
                  (:unexited snapshot)
                  (:rejected snapshot))
          (:active snapshot))]
    (vec
     (sort
      (set
       (concat owning
               (map second (:in-flight snapshot))))))))

(defn- v3-drained? [snapshot]
  (and (empty? (:active snapshot))
       (empty? (:unexited snapshot))
       (empty? (:rejected snapshot))
       (empty? (:in-flight snapshot))))

(defn- drain-unexited!
  "Waits a bounded interval for every v3 worker to release ownership
  (:exit/:abort), every rejected spawn to receive its balancing :abort, and
  every lifecycle callback to finish. Returns true when the scope is safe to
  restore, false when ownership or a callback remains at the deadline."
  [state timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [snap @state]
        (if (v3-drained? snap)
          true
          (if (<= deadline (System/nanoTime))
            false
            (do
              (Thread/sleep 2)
              (recur))))))))

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
          (contains? config-native-operations (nth key 1)))
    nil

    ;; Legacy five-element :foreign-function key, accepted for backward
    ;; compatibility and canonicalized to capture? false.
    (and (vector? key)
         (= 5 (count key))
         (= :foreign-function (nth key 0))
         (string? (nth key 1))
         (vector? (nth key 2))
         (every? keyword? (nth key 2))
         (keyword? (nth key 3))
         (boolean? (nth key 4)))
    nil

    ;; Canonical six-element :foreign-function key.
    (and (vector? key)
         (= 6 (count key))
         (= :foreign-function (nth key 0))
         (string? (nth key 1))
         (vector? (nth key 2))
         (every? keyword? (nth key 2))
         (keyword? (nth key 3))
         (boolean? (nth key 4))
         (boolean? (nth key 5)))
    nil

    :else
    (throw
     (ex-info
      "run-controlled :ffi-handlers entry has a malformed key"
      {:type :jolt.sim.runtime/invalid-config
       :handler-key key}))))

(defn- canonical-handler-key
  "Canonicalizes a validated :ffi-handlers key to the six-element internal
  [:foreign-function symbol argument-types return-type blocking? capture?]
  identity. A legacy five-element foreign-function key has no capture? term
  and canonicalizes to false; every other validated key (native-operation, or
  an already six-element foreign-function key) passes through unchanged."
  [key]
  (if (and (= 5 (count key)) (= :foreign-function (nth key 0)))
    (conj key false)
    key))

(defn- validate-ffi-handlers!
  "Validates every :ffi-handlers key/value pair, then returns the map
  canonicalized to six-element :foreign-function keys. Rejects a config that
  supplies both a legacy five-element key and its equivalent six-element
  capture?-false key for the same signature, since that config cannot express
  which handler applies without silently overwriting the other."
  [handlers]
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
  (let [groups (group-by (fn [[key _]] (canonical-handler-key key)) handlers)
        ambiguous (into {} (filter #(> (count (val %)) 1) groups))]
    (when (seq ambiguous)
      (throw
       (ex-info
        "run-controlled :ffi-handlers supplies both a legacy five-element key and its equivalent six-element key for the same signature"
        {:type :jolt.sim.runtime/invalid-config
         :ambiguous-keys (vec (keys ambiguous))})))
    (into {}
          (map (fn [[key value]] [(canonical-handler-key key) value]))
          handlers)))

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
        (vec (sort (remove #{:on-event :ffi-handlers :drain-timeout-ms
                             :future-schedule}
                           (keys config))))]
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
  (let [drain-timeout (:drain-timeout-ms config ::absent)]
    (when-not (or (= drain-timeout ::absent)
                  (and (integer? drain-timeout) (pos? drain-timeout)))
      (throw
       (ex-info "run-controlled :drain-timeout-ms must be a positive integer"
                {:type :jolt.sim.runtime/invalid-config
                 :drain-timeout-ms drain-timeout}))))
  (when (contains? config :ffi-handlers)
    (validate-ffi-handlers! (:ffi-handlers config)))
  (when (contains? config :future-schedule)
    (future-schedule/validate-schedule! (:future-schedule config))))

(defn run-controlled
  "Runs thunk under the simulation controller described by config.

   Resolves the controller ABI (v1, v2, or v3), throwing
   :jolt.sim.runtime/abi-unavailable or /abi-incompatible otherwise. Under v1 an
   explicitly supplied :ffi-handlers key is rejected with
   :jolt.sim.runtime/capability-unavailable. Atomically claims the single
   session so overlapping or nested runs fail closed with
   :jolt.sim.runtime/session-overlap, clears stale controller errors, then
   installs the future lifecycle controller (and, under v2/v3, the FFI controller
   afterwards) before running the unchanged thunk. Restoration is the reverse:
   FFI then future, both attempted on cleanup. The original body failure is
   preserved whenever cleanup succeeds.

   The future controller records an ordered event log of exact
   {:event :task :parent} maps and forwards each to the optional (:on-event
   config) on the hook thread (blocking at :start gates that task). Under v2/v3
   the FFI controller records an ordered :effects log of every validated native
   interception in arrival order; an unhandled effect throws
   :jolt.sim.runtime/unhandled-native-effect before OS access, and handler or
   missing-handler failures are latched locally so application code cannot catch
   them and make the run succeed.

   Under v3 a task owns a worker from :spawn until exactly one :exit/:abort;
   :finish/:cancel settle its future but do not release ownership, so after the
   body returns the scope waits (bounded by :drain-timeout-ms, default 2000) for
   every worker to release and every lifecycle callback to finish before any
   restoration. If a worker cannot drain, the controller is left installed and
   the session is poisoned (:jolt.sim.runtime/session-poisoned on the next run)
   rather than restored unsafely.

   After the body returns, fails closed with :jolt.sim.runtime/controller-error
   if a callback or handler failed (including a supervisor-latched terminal
   failure, normalized through jolt.sim.trace/normalize-error), and with
   :jolt.sim.runtime/tasks-outlive-scope if any task reached :spawn inside the
   scope but did not release worker ownership (v3: :exit/:abort; v1/v2:
   :finish/:cancel) before cleanup completed.

   Under v3, an optional :future-schedule -- a nonempty vector that is an exact
   permutation of 0..N-1 -- drives the first coarse deterministic scheduler
   (see jolt.sim.future-schedule) over unchanged ordinary futures with parent
   zero. Because ABI v3 cannot distinguish the thunk thread from another
   non-hooked raw thread, this first slice requires a quiescent scope with one
   caller-enforced parent-zero spawner. It assigns ordinals in arrival order
   and admits at most one ordinal's body at a time, in the schedule's order,
   releasing the next only after the current ordinal's :finish. A nested
   spawn, a spawn beyond or short of the schedule's length, a pre-worker
   :abort, an out-of-order terminal event, or a cancellation (unsupported in
   this first slice) fails closed with a retained
   :jolt.sim.runtime/schedule-error and still drains through ABI v3
   :exit/:abort. It requires ABI v3 and is otherwise rejected with
   :jolt.sim.runtime/capability-unavailable.

   On success returns {:result value :events vector-of-maps :capabilities
   descriptor}. Under v2/v3 the map also includes :effects, a vector of
   exact validated descriptors in interception-arrival order; their arguments
   are live in-memory evidence and may contain mutable objects such as byte
   arrays. Under v1 :effects is omitted. When :future-schedule is supplied the
   map also includes :schedule-events, the scheduler's deterministic logical
   evidence log of alternating [:admit ordinal] and [:complete ordinal] pairs.
   Raw lifecycle arrival order and task ids remain only in the unchanged
   compatibility :events field."
  [config thunk]
  (validate-run-arguments! config thunk)
  (let [ops (resolve-controller-ops!)
        abi-version (:abi-version ops)
        on-event (:on-event config)]
    (when (and (= abi-version 1) (contains? config :ffi-handlers))
      (throw
       (ex-info
        "run-controlled :ffi-handlers requires an ABI v2 or v3 sim image"
        {:type :jolt.sim.runtime/capability-unavailable})))
    (when (and (not= abi-version 3)
               (contains? config :drain-timeout-ms))
      (throw
       (ex-info
        "run-controlled :drain-timeout-ms requires an ABI v3 sim image"
        {:type :jolt.sim.runtime/capability-unavailable})))
    (when (and (not= abi-version 3)
               (contains? config :future-schedule))
      (throw
       (ex-info
        "run-controlled :future-schedule requires an ABI v3 sim image"
        {:type :jolt.sim.runtime/capability-unavailable})))
    (let [ffi-handlers (if (contains? config :ffi-handlers)
                         (validate-ffi-handlers! (:ffi-handlers config))
                         {})
          schedule (when-let [future-schedule (:future-schedule config)]
                     (future-schedule/scheduler future-schedule on-event))
          effective-on-event (if schedule (:on-event schedule) on-event)
          state
          (atom {:events []
                 :seen #{}
                 :active #{}
                 :started #{}
                 :unexited #{}
                 :settled #{}
                 :released #{}
                 :rejected #{}
                 :in-flight #{}
                 :local-errors []
                 :ffi-errors []
                 :closed? false})
          effects-log (atom [])
          controller (make-controller effective-on-event state abi-version)
          ffi-capable? (#{2 3} abi-version)
          ffi-controller (when ffi-capable?
                           (make-ffi-controller
                            ffi-handlers state effects-log
                            (get-in (:descriptor ops) [:ffi-interception :descriptor-version])))
          drain-timeout-ms (or (:drain-timeout-ms config) 2000)]
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
          (let [effective-thunk
                (if schedule ((:wrap-thunk schedule) thunk) thunk)
                outcome
                (try
                  {:ok? true :value (effective-thunk)}
                  (catch :default error
                    {:ok? false :error error}))
                _ (close-state! state)
                ;; Under v3, after the body returns the scope waits (bounded)
                ;; for every worker to release ownership and every lifecycle
                ;; callback to finish before any restoration is attempted.
                _drain-attempt
                (when (= abi-version 3)
                  (drain-unexited! state drain-timeout-ms))
                snapshot @state
                ;; Recheck the atomic snapshot after the deadline. A worker may
                ;; have released in the narrow interval between the last clock
                ;; check and this read; such a fully drained state is safe.
                drained? (or (not= abi-version 3)
                             (v3-drained? snapshot))
                latched ((:controller-errors ops))
                errors (controller-errors snapshot latched)
                schedule-failure
                (when schedule ((:failure schedule)))
                gate-aborted-body?
                (and (not (:ok? outcome))
                     (future-schedule/gate-aborted? (:error outcome)))
                outliving (outliving-tasks abi-version snapshot)]
            (cond
              ;; A caught nested spawn, cancellation, pre-worker abort, or
              ;; other scheduler violation remains the primary typed failure
              ;; after every owned worker drains. Later gate-abort sentinels
              ;; must not replace it.
              (and schedule-failure drained?)
              (throw schedule-failure)

              ;; Preserve the original body throw when v3 drainage and exact
              ;; controller restoration remain possible, matching the v1/v2
              ;; contract. v1/v2 retain their historical immediate body-error
              ;; precedence because they have no worker-exit evidence. An
              ;; internal gate-abort sentinel instead defers to the callback
              ;; errors that caused the scheduler to abort.
              (and (not (:ok? outcome))
                   (not gate-aborted-body?)
                   (or (not= abi-version 3) drained?))
              (throw (:error outcome))

              ;; A callback error still fails closed when the body itself
              ;; returned normally or only observed the scheduler's internal
              ;; gate-abort sentinel.
              (and (or (:ok? outcome) gate-aborted-body?)
                   (seq errors))
              (throw
               (ex-info
                "A controller or native-effect callback failed"
                (cond-> {:type :jolt.sim.runtime/controller-error
                         :errors errors
                         :events (:events snapshot)}
                  ffi-capable? (assoc :effects @effects-log))))

              (and (not (:ok? outcome))
                   (or (not= abi-version 3) drained?))
              (throw (:error outcome))

              (or (not drained?) (seq outliving))
              (throw
               (ex-info
                "Tasks spawned inside run-controlled outlived its scope"
                 {:type :jolt.sim.runtime/tasks-outlive-scope
                  :tasks outliving}))

              :else
              (let [base {:result (:value outcome)
                          :events (:events snapshot)
                          :capabilities (:descriptor ops)}]
                (cond-> base
                  ffi-capable? (assoc :effects @effects-log)
                  schedule (assoc :schedule-events ((:evidence schedule)))))))
          (finally
            ;; Closing before restore rejects late lifecycle starts. Under v3
            ;; restoration is refused while any worker still owns or any
            ;; lifecycle callback remains in flight: such restoration would be
            ;; unsafe, so the controller is left installed and the session is
            ;; poisoned instead.
            (try
              (close-state! state)
              (finally
                (let [final @state]
                  (if (and (= abi-version 3)
                           (not (v3-drained? final)))
                    (reset! session-state :poisoned)
                    (restore-controllers!
                     ops @ffi-token @future-token)))))))))))

(defmacro defsim
  "Defines a marked scenario named name that runs body under run-controlled with
  config. The body is ordinary Jolt code; defsim only installs the controller,
  event capture, and cleanup around it.

  The generated function has two arities. `([] ...)` preserves the original
  no-argument contract. `([runtime-overrides] ...)` requires a map and merges it
  over the declared config before the run, allowing an external harness to
  supply one `:future-schedule` without rewriting or wrapping the application
  body. The var carries `:jolt.sim/scenario true` metadata so a process worker
  can fail closed instead of invoking an arbitrary resolved function.

  The expansion references the fully qualified run-controlled, so the scenario
  is callable from any namespace. Not coupled to clojure.test."
  [name config & body]
  (let [scenario-name
        (with-meta name (assoc (meta name) :jolt.sim/scenario true))]
    `(defn ~scenario-name
       ([]
        (~scenario-name {}))
       ([runtime-overrides#]
        (when-not (map? runtime-overrides#)
          (throw
           (ex-info
            "defsim runtime overrides must be a map"
            {:type :jolt.sim.runtime/invalid-config
             :scenario '~name
             :runtime-overrides runtime-overrides#})))
        (let [base-config# ~config]
          ;; Keep run-controlled authoritative for malformed declared config.
          ;; Passing it through unchanged preserves the original no-arg error.
          (jolt.sim.runtime/run-controlled
           (if (map? base-config#)
             (merge base-config# runtime-overrides#)
             base-config#)
           (fn [] ~@body)))))))
