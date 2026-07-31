(ns jolt.sim.runtime
  "Test-only adapter from ordinary Jolt code to the optional controller exposed
  by a sim-enabled Jolt image.

  This unreleased adapter supports one exact current controller contract: ABI
  v4 with worker-ownership lifecycle events, descriptor-version 3 FFI
  interception, and scoped native proceed routing. Until jolt-sim has a public
  release, a future ABI bump replaces this contract in place; intermediate
  development ABIs remain in Git history rather than accumulating compatibility
  branches.

  Core exposes the contract only in sim images through jolt.internal.sim:

    capabilities
    install-controller! / restore-controller!
    controller-errors / clear-controller-errors!
    install-ffi-controller! / restore-ffi-controller!
    install-ffi-routing-controller!

  An ordinary released image has no such namespace. Every symbol is resolved
  dynamically, never required at compile time, so this namespace still loads
  and reports the capability unavailable there. A partial namespace or any
  descriptor other than the exact current literal fails as ABI-incompatible.

  The lifecycle controller receives (event id parent) for :spawn, :start,
  :finish, :cancel, :exit, and :abort. A task owns a worker from :spawn through
  exactly one :exit/:abort; settlement at :finish/:cancel does not release that
  ownership. Cleanup drains all owned workers and in-flight callbacks before
  restoring the strict-LIFO controller tokens.

  run-controlled is hermetic by default: the established one-argument FFI
  controller substitutes registered handlers and blocks unhandled effects
  before OS access. :observe proceeds every intercepted call through its exact
  native branch. :hybrid substitutes registered handlers and permits a native
  miss only when modeled-resource provenance makes it safe. Every controlled
  run records lifecycle events, exact FFI descriptors, and correlated route
  evidence. Optional :future-schedule gates ordinary futures over the same
  current lifecycle contract.

  Raw threads and executor tasks do not emit lifecycle ownership events. A
  controlled body must join them before returning if they can perform FFI;
  otherwise safe restoration and complete route evidence are not claimed."
  (:require [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.trace :as trace]))

(def ^:private proceed-routing-contract
  {:controller-arity 2
   :proceed-arity 0
   :single-use true
   :dynamic-extent true
   :owner-thread true
   :scoped-byte-array-release :runtime-owned})

(def ^:private supported-descriptor
  {:abi-version 4
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel :exit :abort]
   :ffi-interception
   {:descriptor-version 3
    :kinds [:foreign-function :native-operation]
    :arguments :live
    :task-identity :future-lifecycle
    :native-operations [:load-library :loaded? :alloc :free
                        :read :write :sizeof :read-bytes
                        :write-bytes :read-array :write-array
                        :borrow-byte-array :release-byte-array
                        :ptr->string :string->ptr]
    :proceed-routing proceed-routing-contract}})

(def ^:private controller-events
  (set (:events supported-descriptor)))

(def ^:private drain-events
  "Terminal events accepted (and applied) during scope closing for drainage.
  They let :exit/:abort balance outstanding worker ownership after the body
  closes to new work."
  #{:finish :cancel :exit :abort})

(def ^:private native-operations
  (set (get-in supported-descriptor
               [:ffi-interception :native-operations])))

;; Handler config validation runs before ABI resolution but uses the exact
;; current operation set.
(def ^:private config-native-operations
  native-operations)

(def ^:private ffi-kinds
  (set (get-in supported-descriptor [:ffi-interception :kinds])))

(def ^:private capabilities-sym 'jolt.internal.sim/capabilities)
(def ^:private install-sym 'jolt.internal.sim/install-controller!)
(def ^:private restore-sym 'jolt.internal.sim/restore-controller!)
(def ^:private errors-sym 'jolt.internal.sim/controller-errors)
(def ^:private clear-errors-sym 'jolt.internal.sim/clear-controller-errors!)
(def ^:private install-ffi-sym 'jolt.internal.sim/install-ffi-controller!)
(def ^:private restore-ffi-sym 'jolt.internal.sim/restore-ffi-controller!)
;; The routing installer takes one controller invoked as
;; (controller descriptor proceed). Both FFI installers share the same restore
;; stack and restoration function.
(def ^:private install-ffi-routing-sym
  'jolt.internal.sim/install-ffi-routing-controller!)

(def ^:private controller-abi-keys
  [:capabilities
   :install-controller!
   :restore-controller!
   :controller-errors
   :clear-controller-errors!
   :install-ffi-controller!
   :restore-ffi-controller!
   :install-ffi-routing-controller!])

;; Single run-controlled session state. Compare-and-set! claims :idle
;; atomically, so overlapping or nested runs fail closed without a separate
;; lock. A failed exact-token restore leaves the adapter :poisoned: another run
;; must not proceed over controller state whose ownership is no longer known.
(def ^:private session-state (atom :idle))

(defn- safe-resolve [sym]
  (try (resolve sym) (catch :default _ nil)))

(defn- resolved-abi-vars
  "Returns all current controller ABI resolution results, including nils."
  []
  {:capabilities (safe-resolve capabilities-sym)
   :install-controller! (safe-resolve install-sym)
   :restore-controller! (safe-resolve restore-sym)
   :controller-errors (safe-resolve errors-sym)
   :clear-controller-errors! (safe-resolve clear-errors-sym)
   :install-ffi-controller! (safe-resolve install-ffi-sym)
   :restore-ffi-controller! (safe-resolve restore-ffi-sym)
   :install-ffi-routing-controller! (safe-resolve install-ffi-routing-sym)})

(defn- validate-descriptor [caps-value]
  (when-not (map? caps-value)
    (throw
     (ex-info "jolt.internal.sim/capabilities did not return a map"
              {:type :jolt.sim.runtime/abi-incompatible
               :capabilities-class (str (class caps-value))})))
  (when-not (= supported-descriptor caps-value)
    (throw
     (ex-info "jolt.internal.sim exposes an incompatible controller ABI"
              {:type :jolt.sim.runtime/abi-incompatible
               :expected supported-descriptor
               :actual caps-value})))
  caps-value)

(defn- resolve-controller-ops!
  "Resolves the controller ABI vars and the validated capabilities descriptor,
  or throws :jolt.sim.runtime/abi-unavailable (no sim image) or
  :jolt.sim.runtime/abi-incompatible (a partial current ABI or any descriptor
  other than the exact current contract)."
  []
  (let [vars (resolved-abi-vars)
        missing
        (vec
         (keep (fn [key]
                 (when-not (vars key) key))
               controller-abi-keys))]
    (cond
      (= (count missing) (count controller-abi-keys))
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
       :clear-controller-errors! @(:clear-controller-errors! vars)
       :install-ffi-controller! @(:install-ffi-controller! vars)
       :restore-ffi-controller! @(:restore-ffi-controller! vars)
       :install-ffi-routing-controller!
       @(:install-ffi-routing-controller! vars)})))

(defn available?
  "True only when the exact current sim controller contract is available.
  Returns false on an ordinary released image where every optional ABI var is
  absent. A partial namespace or incompatible descriptor fails closed instead
  of being reported as merely unavailable."
  []
  (try
    (resolve-controller-ops!)
    true
    (catch :default error
      (if (= :jolt.sim.runtime/abi-unavailable (:type (ex-data error)))
        false
        (throw error)))))

(defn capabilities
  "Resolves and returns the exact current controller capability descriptor.
  The raw controller functions are never exposed publicly.

  Throws typed ex-info tagged :jolt.sim.runtime/abi-unavailable on an ordinary
  image, and :jolt.sim.runtime/abi-incompatible when the image does not expose
  an exact supported descriptor and operation set."
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
                (update :active conj task)
                (update :unexited conj task)))

          :start
          (cond
            (not (contains? seen task))
            (invalid-controller-event! :start-before-spawn record)

            (contains? (:started state) task)
            (invalid-controller-event! :duplicate-start record)

            :else
            (update state :started conj task))

          (:finish :cancel)
          (cond
            (not (contains? active task))
            (invalid-controller-event! :terminal-without-active-task record)

            :else
            ;; Settle the future; the worker remains :unexited until exactly one
            ;; :exit/:abort releases its ownership.
            (-> state
                (update :active disj task)
                (update :settled conj task)))

          ;; A worker may exit only after :finish/:cancel settled its future.
          ;; :abort is the distinct pre-worker path and therefore removes both
          ;; active and unexited ownership without requiring settlement.
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
  run. Late drainage events are applied and still invoke on-event. A rejected
  late :spawn is recorded before failing so the core's synchronous balancing
  :abort can be accepted without inventing worker ownership; that special
  :abort is recorded but not forwarded to on-event."
  [state record]
  (loop []
    (let [before @state]
      (if (:closed? before)
        (let [event (:event record)]
          (cond
            (#{:spawn :start} event)
            (let [after (reject-event-after-close before record)]
              (if (compare-and-set! state before after)
                :reject
                (recur)))

            (and (= :abort event)
                 (contains? (:rejected before) (:task record)))
            (let [after (balance-rejected-spawn before record)]
              (if (compare-and-set! state before after)
                :balance
                (recur)))

            (contains? drain-events event)
            (let [after (apply-event before record)]
              (if (compare-and-set! state before after)
                :invoke
                (recur)))

            :else
            :ignore))
        (let [after (apply-event before record)]
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
  replace the application result. Late drainage events still reach on-event
  and remain in-flight until that callback returns."
  [on-event state]
  (fn controller [event id parent]
    (let [record {:event event :task id :parent parent}
          error-recorded? (volatile! false)]
      (try
        (validate-controller-event! controller-events record)
        (let [mode (begin-event! state record)]
          (when-not (= :ignore mode)
            ;; Every mode except :ignore inserted an in-flight entry. Clear it
            ;; only after callback delivery or rejection/error latching.
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
          (throw error))))))

;; ---- Established FFI interception -------------------------------------

(defn- invalid-ffi-descriptor! [reason descriptor]
  (throw
   (ex-info
    "The sim image emitted a malformed FFI interception descriptor"
    {:type :jolt.sim.runtime/invalid-ffi-descriptor
     :reason reason
     :descriptor descriptor})))

(def ^:private foreign-function-keys
  #{:kind :task :arguments :symbol :argument-types :return-type :blocking?
    :capture-native-error?})

(def ^:private native-operation-keys
  #{:kind :task :arguments :operation})

(defn- validate-ffi-descriptor!
  "Validates one intercepted call against the exact current descriptor-version
  3 shape. Every foreign descriptor carries Boolean :capture-native-error?;
  native descriptors admit the current 15-operation set."
  [descriptor]
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
        (when-not (= (count (:argument-types descriptor))
                     (count arguments))
          (invalid-ffi-descriptor! :argument-count-mismatch descriptor))
        (when-not (keyword? (:return-type descriptor))
          (invalid-ffi-descriptor! :invalid-return-type descriptor))
        (when-not (boolean? (:blocking? descriptor))
          (invalid-ffi-descriptor! :invalid-blocking descriptor))
        (when-not (boolean? (:capture-native-error? descriptor))
          (invalid-ffi-descriptor! :invalid-capture-native-error descriptor)))
      :native-operation
      (do
        (when-not (= native-operation-keys (set (keys descriptor)))
          (invalid-ffi-descriptor! :native-operation-key-mismatch descriptor))
        (when-not (contains? native-operations (:operation descriptor))
          (invalid-ffi-descriptor! :unknown-operation descriptor))))
    descriptor))

(defn- descriptor-handler-key
  "Computes the canonical six-element handler identity for a foreign call."
  [descriptor]
  (case (:kind descriptor)
    :native-operation
    [:native-operation (:operation descriptor)]
    :foreign-function
    [:foreign-function (:symbol descriptor) (:argument-types descriptor)
     (:return-type descriptor) (:blocking? descriptor)
     (:capture-native-error? descriptor)]))

;; Hybrid routing must distinguish an intentionally modeled value from a
;; legacy hermetic handler result. These wrappers are deliberately ordinary
;; immutable maps: handler packs can construct them without depending on a
;; runtime type, while the namespaced keys keep accidental collisions remote.
(def ^:private handler-result-kind-key ::handler-result)
(def ^:private handler-result-value-key ::value)
(def ^:private handler-result-span-key ::span)
(def ^:private substitute-result-keys
  (set [handler-result-kind-key handler-result-value-key]))
(def ^:private modeled-resource-result-keys
  (set [handler-result-kind-key handler-result-value-key]))
(def ^:private modeled-resource-span-result-keys
  (set [handler-result-kind-key handler-result-value-key
        handler-result-span-key]))

(defn substitute
  "Marks value as an intentional non-resource substitution for hybrid routing.

  Hermetic handlers may continue returning raw values; hybrid handler
  functions must return substitute or modeled-resource so a later native
  fallback cannot silently treat an unclassified model result as real. A nil
  handler-map value remains the shorthand for an explicit nil substitution.
  Known positive pointer-producing descriptors reject substitute;
  borrow-byte-array specifically requires a positive modeled-resource because
  the runtime cannot accept a null borrowed pointer. Handler packs must classify
  integer handles hidden behind scalar ABI types themselves."
  [value]
  {handler-result-kind-key :substitute
   handler-result-value-key value})

(defn modeled-resource
  "Marks value as a model-owned numeric resource for hybrid FFI routing.

  The optional positive span declares the half-open resource interval
  [value,value+span). Without it, alloc and borrow-byte-array infer their span
  from the intercepted descriptor; other calls default to one resource id.
  Hybrid native fallback truncates numeric arguments as core does and rejects
  any result in a recorded model-owned interval. Use disjoint high fake ids in
  handler packs."
  ([value]
   {handler-result-kind-key :modeled-resource
    handler-result-value-key value})
  ([value span]
   (when-not (and (integer? span) (pos? span))
     (throw
      (ex-info
       "modeled-resource span must be a positive integer"
       {:type :jolt.sim.runtime/invalid-modeled-resource
        :span span})))
   {handler-result-kind-key :modeled-resource
    handler-result-value-key value
    handler-result-span-key span}))

(defn- invalid-capture-result! [descriptor result]
  (throw
   (ex-info
    "A captured :foreign-function handler must return a vector of exactly two elements"
    {:type :jolt.sim.runtime/invalid-capture-result
     :descriptor descriptor
     :result result})))

(defn- invalid-handler-result! [reason descriptor result]
  (throw
   (ex-info
    "A hybrid FFI handler returned an invalid or unclassified result"
    {:type :jolt.sim.runtime/invalid-handler-result
     :reason reason
     :descriptor descriptor
     :result result})))

(defn- decode-handler-result!
  "Returns {:kind :legacy/:substitute/:modeled-resource :value value}.
  Legacy raw function returns are accepted only by hermetic routing."
  [ffi-mode descriptor handler-fn result]
  (if (nil? handler-fn)
    {:kind :substitute :value nil}
    (let [tagged? (and (map? result)
                       (contains? result handler-result-kind-key))
          kind (when tagged? (get result handler-result-kind-key))
          keys-set (when tagged? (set (keys result)))]
      (cond
        (= :substitute kind)
        (if (= substitute-result-keys keys-set)
          {:kind :substitute
           :value (get result handler-result-value-key)}
          (invalid-handler-result! :malformed-substitute descriptor result))

        (= :modeled-resource kind)
        (if (or (= modeled-resource-result-keys keys-set)
                (= modeled-resource-span-result-keys keys-set))
          (cond-> {:kind :modeled-resource
                   :value (get result handler-result-value-key)}
            (contains? result handler-result-span-key)
            (assoc :span (get result handler-result-span-key)))
          (invalid-handler-result! :malformed-modeled-resource
                                   descriptor result))

        tagged?
        (invalid-handler-result! :unknown-wrapper-kind descriptor result)

        (= :hybrid ffi-mode)
        (invalid-handler-result! :unclassified-result descriptor result)

        :else
        {:kind :legacy :value result}))))

(defn- validate-capture-result! [descriptor result]
  (when (and (= :foreign-function (:kind descriptor))
             (true? (:capture-native-error? descriptor))
             (not (and (vector? result) (= 2 (count result)))))
    (invalid-capture-result! descriptor result))
  result)

(defn- primary-handler-result [descriptor result]
  (if (and (= :foreign-function (:kind descriptor))
           (true? (:capture-native-error? descriptor)))
    (first result)
    result))

(def ^:private pointer-producing-native-operations
  #{:alloc :borrow-byte-array :string->ptr})

(def ^:private pointer-ffi-types #{:pointer :void*})

(defn- pointer-producing-descriptor? [descriptor]
  (or (and (= :native-operation (:kind descriptor))
           (or (contains? pointer-producing-native-operations
                          (:operation descriptor))
               (and (= :read (:operation descriptor))
                    (contains? pointer-ffi-types
                               (get (:arguments descriptor) 1)))))
      (and (= :foreign-function (:kind descriptor))
           (contains? pointer-ffi-types (:return-type descriptor)))))

(defn- validate-hybrid-classification! [descriptor decoded]
  (let [result (:value decoded)
        primary (primary-handler-result descriptor result)
        borrow-byte-array?
        (and (= :native-operation (:kind descriptor))
             (= :borrow-byte-array (:operation descriptor)))
        fixed-native-pointer?
        (and (= :native-operation (:kind descriptor))
             (contains? pointer-producing-native-operations
                        (:operation descriptor)))]
    ;; substitute asserts that a value is not a model-owned resource. Enforce
    ;; that assertion for the resource-producing signatures the generic ABI can
    ;; identify. Most fixed native pointer producers permit only nil/zero as a
    ;; non-resource result. borrow-byte-array is stricter: core requires a
    ;; positive pointer, so every handled borrow must return a positive modeled
    ;; resource. Pointer-typed reads/foreign calls may additionally use negative
    ;; API failure sentinels. Every other numeric pointer must enter the
    ;; provenance ledger.
    (when (and borrow-byte-array?
               (or (not= :modeled-resource (:kind decoded))
                   (not (and (integer? primary) (pos? primary)))))
      (invalid-handler-result!
       :borrow-requires-positive-modeled-resource descriptor result))
    (when (and (= :substitute (:kind decoded))
               (pointer-producing-descriptor? descriptor)
               (number? primary)
               (if fixed-native-pointer?
                 (not (zero? primary))
                 (pos? primary)))
      (invalid-handler-result!
       :resource-requires-modeled-resource descriptor result)))
  decoded)

(defn- inferred-resource-span [descriptor]
  (let [candidate
        (when (= :native-operation (:kind descriptor))
          (case (:operation descriptor)
            :alloc (first (:arguments descriptor))
            :borrow-byte-array (get (:arguments descriptor) 2)
            nil))]
    (if (and (integer? candidate) (pos? candidate)) candidate 1)))

(defn- register-modeled-resource!
  [resource-ledger descriptor handler-key decoded]
  (when (= :modeled-resource (:kind decoded))
    (let [result (:value decoded)
          base (primary-handler-result descriptor result)
          span (if (contains? decoded :span)
                 (:span decoded)
                 (inferred-resource-span descriptor))]
      (when-not (and (integer? base) (not (neg? base)))
        (throw
         (ex-info
          "modeled-resource must wrap a non-negative integer primary result"
          {:type :jolt.sim.runtime/invalid-modeled-resource
           :descriptor descriptor
           :result result})))
      (when-not (and (integer? span) (pos? span))
        (throw
         (ex-info
          "modeled-resource span must be a positive integer"
          {:type :jolt.sim.runtime/invalid-modeled-resource
           :descriptor descriptor
           :span span})))
      (swap! resource-ledger conj
             {:base base
              :span span
              :handler-key handler-key
              :descriptor descriptor})))
  nil)

(defn- native-truncated-number [argument]
  ;; Core's jnum->exact is (exact (truncate n)). Keep integer precision, and
  ;; otherwise use Jolt's exact bigint conversion, which applies the same
  ;; truncate-toward-zero rule without routing exact ratios through DOUBLE.
  ;; NaN/infinities cannot be made exact; leave them unmatched so core retains
  ;; its ordinary native/application error semantics before OS execution.
  (cond
    (integer? argument) argument
    (number? argument) (try
                         (bigint argument)
                         (catch :default _ nil))
    :else nil))

(defn- modeled-resource-hit [resource-ledger descriptor]
  (some
   identity
   (map-indexed
    (fn [argument-index argument]
      (when-let [native-argument (native-truncated-number argument)]
        (when-let [resource
                   (some
                    (fn [{:keys [base span] :as resource}]
                      (when (and (<= base native-argument)
                                 (< native-argument (+ base span)))
                        resource))
                    @resource-ledger)]
          {:argument-index argument-index
           :argument argument
           :native-argument native-argument
           :resource resource})))
    (:arguments descriptor))))

(defn- record-arrival! [effect-trace-log entry]
  "Atomically appends one route decision in interception-arrival order.
  Handler or native completion order cannot reorder this evidence."
  (swap! effect-trace-log conj entry)
  nil)

(defn- effect-evidence [effect-trace-log]
  ;; One atom snapshot guarantees positional correlation even if a caller
  ;; violates the documented quiescence requirement with an unmanaged thread.
  (let [effect-trace @effect-trace-log]
    {:effects (mapv :descriptor effect-trace)
     :effect-trace effect-trace}))

(defn- record-ffi-error! [state descriptor category error]
  (swap! state update :ffi-errors conj
         (cond-> {:ffi-error category :descriptor descriptor}
           (some? error) (assoc :error error))))

(defn- ffi-error-category [validated? error]
  (if-not validated?
    :invalid-descriptor
    (case (:type (ex-data error))
      :jolt.sim.runtime/invalid-handler-result :invalid-handler-result
      :jolt.sim.runtime/invalid-modeled-resource :invalid-handler-result
      :jolt.sim.runtime/invalid-proceed :invalid-proceed
      :handler-error)))

(defn- make-ffi-controller
  "Returns the established hermetic FFI controller. It validates every incoming
  descriptor against the exact current shape, records its handler/blocked
  decision in arrival order before execution, and
  dispatches to the registered handler (nil handler is a valid substitution
  returning nil). An unhandled effect throws
  :jolt.sim.runtime/unhandled-native-effect before any OS access. When a
  :foreign-function descriptor's :capture-native-error? is true, the handler's
  returned value must be a vector of exactly two elements; a wrong shape
  throws :jolt.sim.runtime/invalid-capture-result. Handler, missing-handler,
  malformed-descriptor, and malformed-capture-result failures are latched in
  state so application code cannot catch the thrown exception and make the run
  succeed."
  [handlers state effect-trace-log]
  (fn ffi-controller [descriptor]
    (let [latched? (volatile! false)
          validated? (volatile! false)]
      (try
        (validate-ffi-descriptor! descriptor)
        (vreset! validated? true)
        (let [key (descriptor-handler-key descriptor)
              entry (find handlers key)]
          (if entry
            (let [handler-fn (val entry)
                  _ (record-arrival!
                     effect-trace-log
                     {:mode :hermetic :route :handler
                      :handler-key key :descriptor descriptor})
                  raw-result (if (some? handler-fn)
                               (handler-fn descriptor)
                               nil)
                  decoded (decode-handler-result!
                           :hermetic descriptor handler-fn raw-result)
                  result (:value decoded)]
              (validate-capture-result! descriptor result))
            (do
              (record-arrival!
               effect-trace-log
               {:mode :hermetic :route :blocked
                :reason :unhandled-native-effect
                :handler-key key :descriptor descriptor})
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
            (let [category (ffi-error-category @validated? error)]
              (record-ffi-error! state descriptor category error))
            (vreset! latched? true))
          (throw error))))))

(defn- invalid-proceed! [descriptor proceed]
  (throw
   (ex-info
    "The routing controller received an invalid proceed continuation"
    {:type :jolt.sim.runtime/invalid-proceed
     :descriptor descriptor
     :proceed-class (str (class proceed))})))

(defn- make-ffi-routing-controller
  "Returns the two-argument routing controller for :observe or :hybrid.
  Observe always invokes proceed. Hybrid dispatches configured handlers first
  and otherwise invokes proceed only when no model-owned numeric resource is
  present in the descriptor's top-level arguments. Native exceptions propagate
  with ordinary application semantics and are deliberately not controller
  errors. Policy, handler, and provenance failures remain latched fail closed."
  [ffi-mode handlers state effect-trace-log resource-ledger]
  (fn ffi-routing-controller [descriptor proceed]
    (let [latched? (volatile! false)
          validated? (volatile! false)
          proceeding? (volatile! false)]
      (try
        (validate-ffi-descriptor! descriptor)
        (vreset! validated? true)
        (when-not (fn? proceed)
          (invalid-proceed! descriptor proceed))
        (let [key (descriptor-handler-key descriptor)
              entry (find handlers key)]
          (if (= :observe ffi-mode)
            (do
              (record-arrival!
               effect-trace-log
               {:mode :observe :route :native :descriptor descriptor})
              (vreset! proceeding? true)
              (proceed))
            (if entry
              (let [handler-fn (val entry)
                    _ (record-arrival!
                       effect-trace-log
                       {:mode :hybrid :route :handler
                        :handler-key key :descriptor descriptor})
                    raw-result (if (some? handler-fn)
                                 (handler-fn descriptor)
                                 nil)
                    decoded (decode-handler-result!
                             :hybrid descriptor handler-fn raw-result)
                    result (validate-capture-result!
                            descriptor (:value decoded))
                    decoded (validate-hybrid-classification!
                             descriptor (assoc decoded :value result))]
                (register-modeled-resource!
                 resource-ledger descriptor key decoded)
                result)
              (if-let [hit (modeled-resource-hit resource-ledger descriptor)]
                (let [error
                      (ex-info
                       "A model-owned resource cannot cross into native fallback"
                       {:type :jolt.sim.runtime/modeled-resource-native-fallback
                        :descriptor descriptor
                        :argument-index (:argument-index hit)
                        :argument (:argument hit)
                        :native-argument (:native-argument hit)
                        :resource (:resource hit)})]
                  (record-arrival!
                   effect-trace-log
                   {:mode :hybrid :route :blocked
                    :reason :modeled-resource-native-fallback
                    :descriptor descriptor})
                  (record-ffi-error!
                   state descriptor :modeled-resource-native-fallback error)
                  (vreset! latched? true)
                  (throw error))
                (do
                  (record-arrival!
                   effect-trace-log
                   {:mode :hybrid :route :native :descriptor descriptor})
                  (vreset! proceeding? true)
                  (proceed))))))
        (catch :default error
          ;; A proceed failure is an ordinary native/application exception. It
          ;; must remain catchable by the unchanged body and never poison the
          ;; controller latch. Every other routing failure is policy-owned.
          (when (and (not @proceeding?) (not @latched?))
            (record-ffi-error!
             state descriptor (ffi-error-category @validated? error) error)
            (vreset! latched? true))
          (throw error))))))

;; ---- config / session / run -------------------------------------------

(def ^:private ffi-modes #{:hermetic :observe :hybrid})

(defn- close-state! [state]
  (swap! state assoc :closed? true))

(defn- outliving-tasks [snapshot]
  ;; A task outlives the scope while its worker still owns (has not reached
  ;; :exit/:abort), a rejected spawn has not received its balancing :abort, or
  ;; a lifecycle callback is still executing.
  (let [owning (concat (:active snapshot)
                       (:unexited snapshot)
                       (:rejected snapshot))]
    (vec
     (sort
      (set
       (concat owning
               (map second (:in-flight snapshot))))))))

(defn- drained? [snapshot]
  (and (empty? (:active snapshot))
       (empty? (:unexited snapshot))
       (empty? (:rejected snapshot))
       (empty? (:in-flight snapshot))))

(defn- drain-owned!
  "Waits a bounded interval for every worker to release ownership
  (:exit/:abort), every rejected spawn to receive its balancing :abort, and
  every lifecycle callback to finish. Returns true when the scope is safe to
  restore, false when ownership or a callback remains at the deadline."
  [state timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [snap @state]
        (if (drained? snap)
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

(defn normalize-ffi-handlers
  "Validates and canonicalizes an FFI handler map using run-controlled's exact
  current contract. Legacy five-element foreign-function keys become canonical
  six-element keys with capture disabled. This pure helper is public so
  extension and handler-pack tooling cannot drift from runtime validation."
  [handlers]
  (validate-ffi-handlers! handlers))

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
  ;; Validate pure config before resolving the optional runtime capability.
  (let [unknown-keys
        (vec (sort (remove #{:on-event :ffi-handlers :ffi-mode
                             :drain-timeout-ms :future-schedule}
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
  (when (contains? config :ffi-mode)
    (when-not (contains? ffi-modes (:ffi-mode config))
      (throw
       (ex-info
        "run-controlled :ffi-mode must be :hermetic, :observe, or :hybrid"
        {:type :jolt.sim.runtime/invalid-config
         :ffi-mode (:ffi-mode config)}))))
  (when (and (= :observe (:ffi-mode config))
             (contains? config :ffi-handlers))
    (throw
     (ex-info
      "run-controlled :observe mode does not accept :ffi-handlers"
      {:type :jolt.sim.runtime/invalid-config
       :ffi-mode :observe})))
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

   Resolves the exact current controller contract, throwing
   :jolt.sim.runtime/abi-unavailable or /abi-incompatible otherwise. Atomically
   claims the single session, clears stale errors, installs the lifecycle
   controller and then one FFI controller, and runs the unchanged thunk.
   :ffi-mode defaults to :hermetic and uses the established one-argument
   fail-closed controller. :observe/:hybrid install the routing controller;
   observe proceeds every call, while hybrid proceeds misses only after its
   modeled-resource guard. Restoration is FFI then future, with both attempted.

   The future controller records an ordered event log of exact
   {:event :task :parent} maps and forwards each to the optional (:on-event
   config) on the hook thread (blocking at :start gates that task). The FFI
   controller records every validated native interception plus correlated
   :effect-trace route evidence.
   Hermetic unhandled effects throw :jolt.sim.runtime/unhandled-native-effect
   before OS access. Handler and routing-policy failures are latched locally so
   application code cannot catch them and make the run succeed; native proceed
   exceptions are ordinary body exceptions and are not latched.

   A task owns a worker from :spawn until exactly one :exit/:abort;
   :finish/:cancel settle its future but do not release ownership. After the
   body returns the scope waits (bounded by :drain-timeout-ms, default 2000) for
   every worker and callback before restoration. If the scope cannot drain,
   controllers stay installed and the session is poisoned rather than restored
   unsafely.

   This ownership guarantee covers hooked ordinary futures only. Raw threads
   and executor tasks are not visible to the lifecycle ABI and must be joined
   before thunk returns if they can perform FFI; run-controlled makes no safe
   restoration or complete-trace claim for an unmanaged outliving thread.

   After the body returns, fails closed with :jolt.sim.runtime/controller-error
   if a callback or handler failed (including a supervisor-latched terminal
   failure, normalized through jolt.sim.trace/normalize-error), and with
   :jolt.sim.runtime/tasks-outlive-scope if any task reached :spawn inside the
   scope but did not release worker ownership through :exit/:abort before
   cleanup completed.

   An optional :future-schedule -- a nonempty exact permutation of 0..N-1 --
   drives the first coarse deterministic scheduler over ordinary futures with
   parent zero. This first slice requires a quiescent scope with one
   caller-enforced parent-zero spawner. Nested or excess spawns, missing spawns,
   pre-worker abort, out-of-order terminal events, and cancellation fail closed
   with a retained :jolt.sim.runtime/schedule-error and still drain ownership.

   On success returns {:result value :events vector-of-maps :capabilities
   descriptor :effects vector :effect-trace vector}. Descriptor arguments are
   live in-memory evidence and may contain mutable objects such as byte arrays.
   When :future-schedule is supplied the map also includes
   :schedule-events, the scheduler's deterministic logical evidence log of
   alternating [:admit ordinal] and [:complete ordinal] pairs. Raw lifecycle
   arrival order and task ids remain in :events."
  [config thunk]
  (validate-run-arguments! config thunk)
  (let [ops (resolve-controller-ops!)
        on-event (:on-event config)
        ffi-mode (get config :ffi-mode :hermetic)]
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
          effect-trace-log (atom [])
          resource-ledger (atom [])
          controller (make-controller effective-on-event state)
          ffi-controller
          (if (= :hermetic ffi-mode)
            (make-ffi-controller ffi-handlers state effect-trace-log)
            (make-ffi-routing-controller
             ffi-mode ffi-handlers state effect-trace-log resource-ledger))
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
          (vreset! ffi-token
                   ((if (= :hermetic ffi-mode)
                      (:install-ffi-controller! ops)
                      (:install-ffi-routing-controller! ops))
                    ffi-controller))
          (let [effective-thunk
                (if schedule ((:wrap-thunk schedule) thunk) thunk)
                outcome
                (try
                  {:ok? true :value (effective-thunk)}
                  (catch :default error
                    {:ok? false :error error}))
                _ (close-state! state)
                ;; After the body returns, wait a bounded interval for every
                ;; worker and lifecycle callback before attempting restoration.
                _drain-attempt
                (drain-owned! state drain-timeout-ms)
                snapshot @state
                ;; Recheck the atomic snapshot after the deadline. A worker may
                ;; have released in the narrow interval between the last clock
                ;; check and this read; such a fully drained state is safe.
                scope-drained? (drained? snapshot)
                latched ((:controller-errors ops))
                errors (controller-errors snapshot latched)
                ffi-evidence (effect-evidence effect-trace-log)
                schedule-failure
                (when schedule ((:failure schedule)))
                gate-aborted-body?
                (and (not (:ok? outcome))
                     (future-schedule/gate-aborted? (:error outcome)))
                outliving (outliving-tasks snapshot)]
            (cond
              ;; A caught nested spawn, cancellation, pre-worker abort, or
              ;; other scheduler violation remains the primary typed failure
              ;; after every owned worker drains. Later gate-abort sentinels
              ;; must not replace it.
              (and schedule-failure scope-drained?)
              (throw schedule-failure)

              ;; Preserve the original body throw once drainage and exact
              ;; restoration remain possible. An internal gate-abort sentinel
              ;; instead defers to the callback errors that caused the abort.
              (and (not (:ok? outcome))
                   (not gate-aborted-body?)
                   scope-drained?)
              (throw (:error outcome))

              ;; A callback error still fails closed when the body itself
              ;; returned normally or only observed the scheduler's internal
              ;; gate-abort sentinel.
              (and (or (:ok? outcome) gate-aborted-body?)
                   (seq errors))
              (throw
               (ex-info
                "A controller or native-effect callback failed"
                (merge {:type :jolt.sim.runtime/controller-error
                        :errors errors
                        :events (:events snapshot)}
                       ffi-evidence)))

              (and (not (:ok? outcome))
                   scope-drained?)
              (throw (:error outcome))

              (or (not scope-drained?) (seq outliving))
              (throw
               (ex-info
                "Tasks spawned inside run-controlled outlived its scope"
                 {:type :jolt.sim.runtime/tasks-outlive-scope
                  :tasks outliving}))

              :else
              (let [base {:result (:value outcome)
                          :events (:events snapshot)
                          :capabilities (:descriptor ops)}]
                (cond-> (merge base ffi-evidence)
                  schedule (assoc :schedule-events ((:evidence schedule)))))))
          (finally
            ;; Restoration is refused while any worker still owns or callback
            ;; remains in flight; controllers stay installed and the session is
            ;; poisoned instead.
            (try
              (close-state! state)
              (finally
                (let [final @state]
                  (if (not (drained? final))
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
