(ns jolt.sim.runtime
  "Test-only adapter from ordinary Jolt code to the optional controller exposed
  by a sim-enabled Jolt image.

  This unreleased adapter supports one exact current controller contract: ABI
  v6, a single composite install/restore over future, FFI, and clock callbacks,
  descriptor-version 8 FFI interception, descriptor-version 1 clock
  interception, and scoped native proceed routing. Until jolt-sim has a public
  release, a future ABI bump replaces this contract in place; intermediate
  development ABIs remain in Git history rather than accumulating compatibility
  branches.

  Core exposes the contract only in sim images through jolt.internal.sim:

    capabilities
    install-controller! / restore-controller!
    controller-errors / clear-controller-errors!
    supervisor-mono-nanos
    read-active-byte-array-view / write-active-byte-array-view!

  An ordinary released image has no such namespace. Every symbol is resolved
  dynamically, never required at compile time, so this namespace still loads
  and reports the capability unavailable there. A partial namespace or any
  descriptor other than the exact current literal fails as ABI-incompatible.

  install-controller! accepts one composite callback map keyed :future, :ffi,
  and :clock, and returns a single restore token; restore-controller! accepts
  that one token. The install is atomic and restores in strict LIFO order.

  The lifecycle controller receives (event id parent) for :spawn, :start,
  :finish, :cancel, :exit, and :abort. A task owns a worker from :spawn through
  exactly one :exit/:abort; settlement at :finish/:cancel does not release that
  ownership. Cleanup drains all owned workers and in-flight callbacks before
  restoring the single composite token.

  The FFI callback is always arity 2 (descriptor proceed). run-controlled is
  hermetic by default: the established FFI controller substitutes registered
  handlers and blocks unhandled effects before OS access, ignoring proceed.
  :observe proceeds every intercepted call through its exact native branch.
  :hybrid substitutes registered handlers and permits a native miss only when
  modeled-resource provenance makes it safe. A registered hybrid handler may
  also return proceed to explicitly request native routing for that exact call,
  still subject to the same provenance guard, and may return
  with-additional-resources to register extra modeled resources -- such as POSIX
  pipe's output descriptors -- alongside its primary classified result. Every
  controlled run records lifecycle events, exact FFI descriptors, and
  correlated route evidence. Optional :future-schedule gates ordinary futures
  over the same current lifecycle contract.

  The clock callback is arity 2 (descriptor proceed). run-controlled accepts an
  optional :clock config; when absent it installs a pass-through clock that
  proceeds every :mono-nanos call so real OS monotonic time remains available.
  The resolved private supervisor-mono-nanos, never an intercepted clock, drives
  drain deadlines so a frozen virtual clock still times out.

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
   :lifo true})

(def ^:private ffi-proceed-routing-contract
  (assoc proceed-routing-contract
         :scoped-byte-array-release :runtime-owned))

(def ^:private supported-descriptor
  {:abi-version 6
   :future-lifecycle true
   :controller-errors true
   :events [:spawn :start :finish :cancel :exit :abort]
   :installation
   {:configuration-keys [:future :ffi :clock]
    :install-arity 1
    :restore-arity 1
    :atomic? true
    :strict-lifo? true
    :future-controller-arity 3
    :ffi-controller-arity 2
    :clock-controller-arity 2}
   :ffi-interception
   {:descriptor-version 8
    :kinds [:foreign-function :native-operation]
    :arguments :live
    :task-identity :future-lifecycle
    :native-operations [:load-library :loaded? :alloc :free
                        :read :write :sizeof :null? :read-bytes
                        :write-bytes :read-array :read-array!
                        :write-array :ptr->string :string->ptr]
    :proceed-routing ffi-proceed-routing-contract
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

;; The host projects these exact arities before a public controller can see a
;; native-operation descriptor. Mirror that projection here so pure adapter
;; tests and defensive validation reject shapes the live ABI can never emit.
(def ^:private native-operation-arities
  {:load-library #{0 1}
   :loaded? #{1}
   :alloc #{1}
   :free #{1}
   :read #{2 3}
   :write #{4}
   :sizeof #{1}
   :null? #{1}
   :read-bytes #{2}
   :write-bytes #{2}
   :read-array #{2}
   :read-array! #{4}
   :write-array #{2 4}
   :ptr->string #{1}
   :string->ptr #{1}})

;; Handler config validation runs before ABI resolution but uses the exact
;; current operation set.
(def ^:private config-native-operations
  native-operations)

(def ^:private ffi-kinds
  (set (get-in supported-descriptor [:ffi-interception :kinds])))

(def ^:private clock-descriptor-keys #{:kind :operation})

(def ^:private clock-operations
  (set (get-in supported-descriptor [:clock-interception :operations])))

(def ^:private mono-nanos-clock-descriptor
  {:kind :clock :operation :mono-nanos})

(def ^:private capabilities-sym 'jolt.internal.sim/capabilities)
(def ^:private install-sym 'jolt.internal.sim/install-controller!)
(def ^:private restore-sym 'jolt.internal.sim/restore-controller!)
(def ^:private errors-sym 'jolt.internal.sim/controller-errors)
(def ^:private clear-errors-sym 'jolt.internal.sim/clear-controller-errors!)
(def ^:private supervisor-mono-nanos-sym 'jolt.internal.sim/supervisor-mono-nanos)
(def ^:private read-active-byte-array-view-sym
  'jolt.internal.sim/read-active-byte-array-view)
(def ^:private write-active-byte-array-view-sym
  'jolt.internal.sim/write-active-byte-array-view!)

(def ^:private controller-abi-keys
  [:capabilities
   :install-controller!
   :restore-controller!
   :controller-errors
   :clear-controller-errors!
   :supervisor-mono-nanos
   :read-active-byte-array-view
   :write-active-byte-array-view!])

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
   :supervisor-mono-nanos (safe-resolve supervisor-mono-nanos-sym)
   :read-active-byte-array-view (safe-resolve read-active-byte-array-view-sym)
   :write-active-byte-array-view!
   (safe-resolve write-active-byte-array-view-sym)})

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
       :supervisor-mono-nanos @(:supervisor-mono-nanos vars)
       :read-active-byte-array-view @(:read-active-byte-array-view vars)
       :write-active-byte-array-view!
       @(:write-active-byte-array-view! vars)})))

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

(defn read-active-byte-array-view
  "Copies len bytes from ptr only when ptr addresses a byte-array pointer loan
  active on the calling thread. Returns a fresh byte-array on a match and nil
  when ptr is outside every active loan. A matched but out-of-bounds span
  fails closed. This grants no acquire, release, or lifetime ownership."
  [ptr len]
  ((:read-active-byte-array-view (resolve-controller-ops!)) ptr len))

(defn write-active-byte-array-view!
  "Copies the complete byte-array src into ptr only when ptr addresses a
  byte-array pointer loan active on the calling thread. Returns the copied
  count on a match and nil when ptr is outside every active loan. The Jolt
  runtime retains all loan and copy-back ownership."
  [ptr src]
  ((:write-active-byte-array-view! (resolve-controller-ops!)) ptr src))

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
    :capture-native-error? :varargs-after})

(def ^:private native-operation-keys
  #{:kind :task :arguments :operation})

;; Exact scalar foreign argument types (descriptor-version 8). Current Jolt
;; scalar metadata is exact, so a public foreign argument type is a primitive
;; keyword only. Recursive by-value aggregate argument types remain rejected;
;; variadic calls are instead identified by an exact :varargs-after boundary
;; (nil for fixed-arity calls, or a positive integer no greater than the
;; argument-type count naming the first variadic position). Exact scalar widths
;; and :capture-native-error? are retained unchanged.
(defn- valid-argument-type? [argument-type]
  (and (keyword? argument-type)
       (nil? (namespace argument-type))))

(defn- valid-argument-types? [argument-types]
  (and (vector? argument-types)
       (every? valid-argument-type? argument-types)))

(defn- valid-varargs-boundary?
  "True when varargs-after is nil (a fixed-arity call) or a positive integer no
  greater than the argument-type count (the first variadic position). Booleans,
  ratios, floats, strings, and namespaced keywords are rejected; only exact
  integer boundaries or nil are accepted."
  [argument-types varargs-after]
  (or (nil? varargs-after)
      (and (integer? varargs-after)
           (pos? varargs-after)
           (<= varargs-after (count argument-types)))))

(defn- validate-ffi-descriptor!
  "Validates one intercepted call against the exact current descriptor-version
  7 shape. Every foreign descriptor carries Boolean :capture-native-error?
  and an exact :varargs-after boundary (nil or a positive integer no greater
  than the argument-type count); native descriptors admit the current
  15-operation set."
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
        (when-not (valid-argument-types? (:argument-types descriptor))
          (invalid-ffi-descriptor! :invalid-argument-types descriptor))
        (when-not (= (count (:argument-types descriptor))
                     (count arguments))
          (invalid-ffi-descriptor! :argument-count-mismatch descriptor))
        (when-not (valid-argument-type? (:return-type descriptor))
          (invalid-ffi-descriptor! :invalid-return-type descriptor))
        (when-not (boolean? (:blocking? descriptor))
          (invalid-ffi-descriptor! :invalid-blocking descriptor))
        (when-not (boolean? (:capture-native-error? descriptor))
          (invalid-ffi-descriptor! :invalid-capture-native-error descriptor))
        (when-not (valid-varargs-boundary? (:argument-types descriptor)
                                          (:varargs-after descriptor))
          (invalid-ffi-descriptor! :invalid-varargs-after descriptor)))
      :native-operation
      (do
        (when-not (= native-operation-keys (set (keys descriptor)))
          (invalid-ffi-descriptor! :native-operation-key-mismatch descriptor))
        (when-not (contains? native-operations (:operation descriptor))
          (invalid-ffi-descriptor! :unknown-operation descriptor))
        (when-not (contains? (get native-operation-arities
                                  (:operation descriptor))
                             (count arguments))
          (invalid-ffi-descriptor! :invalid-native-operation-arity descriptor))))
    descriptor))

(defn- descriptor-handler-key
  "Computes the canonical seven-element handler identity for a foreign call."
  [descriptor]
  (case (:kind descriptor)
    :native-operation
    [:native-operation (:operation descriptor)]
    :foreign-function
    [:foreign-function (:symbol descriptor) (:argument-types descriptor)
     (:return-type descriptor) (:blocking? descriptor)
     (:capture-native-error? descriptor) (:varargs-after descriptor)]))

;; Hybrid routing must distinguish an intentionally modeled value from a
;; legacy hermetic handler result. These wrappers are deliberately ordinary
;; immutable maps: handler packs can construct them without depending on a
;; runtime type, while the namespaced keys keep accidental collisions remote.
(def ^:private handler-result-kind-key ::handler-result)
(def ^:private handler-result-value-key ::value)
(def ^:private handler-result-span-key ::span)
(def ^:private additional-resources-key ::additional-resources)
(def ^:private substitute-result-keys
  (set [handler-result-kind-key handler-result-value-key]))
(def ^:private modeled-resource-result-keys
  (set [handler-result-kind-key handler-result-value-key]))
(def ^:private modeled-resource-span-result-keys
  (set [handler-result-kind-key handler-result-value-key
        handler-result-span-key]))
(def ^:private proceed-result-keys
  (set [handler-result-kind-key]))

(defn- exact-base-handler-result? [result]
  (when (map? result)
    (let [kind (get result handler-result-kind-key)
          keys-set (set (keys result))]
      (case kind
        :substitute
        (= substitute-result-keys keys-set)

        :modeled-resource
        (and (or (= modeled-resource-result-keys keys-set)
                 (= modeled-resource-span-result-keys keys-set))
             (or (not (contains? result handler-result-span-key))
                 (let [span (get result handler-result-span-key)]
                   (and (integer? span) (pos? span)))))

        false))))

(defn substitute
  "Marks value as an intentional non-resource substitution for hybrid routing.

  Hermetic handlers may continue returning raw values; hybrid handler
  functions must return substitute or modeled-resource so a later native
  fallback cannot silently treat an unclassified model result as real. A nil
  handler-map value remains the shorthand for an explicit nil substitution.
  Known positive pointer-producing descriptors reject substitute. Handler
  packs must classify integer handles hidden behind scalar ABI types
  themselves."
  [value]
  {handler-result-kind-key :substitute
   handler-result-value-key value})

(defn modeled-resource
  "Marks value as a model-owned numeric resource for hybrid FFI routing.

  The optional positive span declares the half-open resource interval
  [value,value+span). Without it, alloc infers its span from the intercepted
  descriptor; other calls default to one resource id.
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

(defn proceed
  "Marks a registered hybrid handler's return as an explicit request to route
  this exact intercepted call to its native implementation, instead of
  substituting a modeled value. Valid only from a registered hybrid handler;
  :hermetic dispatch rejects it outright, since no native proceed continuation
  is ever offered to a hermetic handler. The existing modeled-resource
  provenance guard still runs before native execution, so a call whose live
  arguments alias an already-registered modeled resource remains blocked even
  when the selecting handler asks for proceed. The resulting effect-trace
  entry uses :route :native while retaining the selecting handler's identity,
  distinguishing an explicit selection from an ordinary unhandled-descriptor
  native miss."
  []
  {handler-result-kind-key :proceed})

(defn- valid-additional-resource? [resource]
  (and (map? resource)
       (= #{:base :span} (set (keys resource)))
       (integer? (:base resource))
       (not (neg? (:base resource)))
       (integer? (:span resource))
       (pos? (:span resource))))

(defn- invalid-additional-resource! [reason value]
  (throw
   (ex-info
    "An additional modeled resource must be an exact {:base nonnegative-integer :span positive-integer} map"
    {:type :jolt.sim.runtime/invalid-modeled-resource
     :reason reason
     :value value})))

(defn- validate-additional-resources! [additional-resources]
  (when-not (vector? additional-resources)
    (invalid-additional-resource! :not-a-vector additional-resources))
  (doseq [resource additional-resources]
    (when-not (valid-additional-resource? resource)
      (invalid-additional-resource! :malformed-additional-resource resource)))
  additional-resources)

(defn with-additional-resources
  "Wraps a classified substitute or modeled-resource handler result so that,
  once the wrapped result is registered, zero or more additional modeled
  resources are also atomically appended to the same run's resource ledger.
  Each addition must be an exact {:base nonnegative-integer :span
  positive-integer} map declaring its own disjoint half-open interval; the
  base result's own value/span are unaffected. This supports APIs such as
  POSIX pipe, whose primary return is an ordinary status while separate output
  pointers receive modeled descriptors that later native calls must not
  silently alias. Every addition is validated here, eagerly, before any are
  attached; a malformed resource throws immediately rather than reaching the
  ledger. Does not add resource retirement or typed resource domains."
  [handler-result additional-resources]
  (when-not (exact-base-handler-result? handler-result)
    (throw
     (ex-info
      "with-additional-resources must wrap a substitute or modeled-resource result"
      {:type :jolt.sim.runtime/invalid-handler-result
       :reason :additional-resources-target
       :result handler-result})))
  (validate-additional-resources! additional-resources)
  (assoc handler-result additional-resources-key additional-resources))

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
  "Returns {:kind :legacy/:substitute/:modeled-resource/:proceed :value value}
  plus :additional-resources when the wrapped substitute or modeled-resource
  result was built by with-additional-resources. Legacy raw function returns
  are accepted only by hermetic routing. :proceed is accepted only by hybrid
  routing; hermetic dispatch always rejects it."
  [ffi-mode descriptor handler-fn result]
  (if (nil? handler-fn)
    {:kind :substitute :value nil}
    (let [tagged? (and (map? result)
                       (contains? result handler-result-kind-key))
          kind (when tagged? (get result handler-result-kind-key))
          keys-set (when tagged? (set (keys result)))
          has-additional? (and tagged?
                               (contains? result additional-resources-key))
          base-keys-set (if has-additional?
                          (disj keys-set additional-resources-key)
                          keys-set)
          additional (when has-additional?
                       (get result additional-resources-key))]
      (when has-additional?
        (validate-additional-resources! additional))
      (cond
        (= :substitute kind)
        (if (= substitute-result-keys base-keys-set)
          (cond-> {:kind :substitute
                   :value (get result handler-result-value-key)}
            has-additional? (assoc :additional-resources additional))
          (invalid-handler-result! :malformed-substitute descriptor result))

        (= :modeled-resource kind)
        (if (or (= modeled-resource-result-keys base-keys-set)
                (= modeled-resource-span-result-keys base-keys-set))
          (cond-> {:kind :modeled-resource
                   :value (get result handler-result-value-key)}
            (contains? result handler-result-span-key)
            (assoc :span (get result handler-result-span-key))
            has-additional? (assoc :additional-resources additional))
          (invalid-handler-result! :malformed-modeled-resource
                                   descriptor result))

        (= :proceed kind)
        (cond
          (not= :hybrid ffi-mode)
          (invalid-handler-result! :proceed-requires-hybrid-mode
                                   descriptor result)
          (not= proceed-result-keys keys-set)
          (invalid-handler-result! :malformed-proceed descriptor result)
          :else
          {:kind :proceed})

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
  #{:alloc :string->ptr})

(def ^:private pointer-result-type-names #{"pointer" "void*"})

(def ^:private pointer-capable-argument-type-names
  #{"pointer" "void*" "iptr" "uptr"})

(defn- ffi-type-name
  "Returns the host-visible spelling of a scalar FFI type when it can be
  represented without evaluation. Foreign descriptors always carry keywords;
  native read/write calls also accept the string and symbol spellings that
  Jolt's runtime type resolver accepts."
  [ffi-type]
  (cond
    (keyword? ffi-type) (name ffi-type)
    (symbol? ffi-type) (name ffi-type)
    (string? ffi-type) ffi-type
    :else nil))

(defn- pointer-result-type? [ffi-type]
  (contains? pointer-result-type-names (ffi-type-name ffi-type)))

(defn- pointer-capable-argument-type? [ffi-type]
  (contains? pointer-capable-argument-type-names (ffi-type-name ffi-type)))

(defn- pointer-producing-descriptor? [descriptor]
  (or (and (= :native-operation (:kind descriptor))
           (or (contains? pointer-producing-native-operations
                          (:operation descriptor))
               (and (= :read (:operation descriptor))
                    (pointer-result-type?
                     (get (:arguments descriptor) 1)))))
      (and (= :foreign-function (:kind descriptor))
           (pointer-result-type? (:return-type descriptor)))))

;; Exact pointer-bearing argument positions for the current 15-operation native
;; contract. free/read/read-bytes/write-bytes/read-array/read-array!/
;; write-array/ptr->string each take their pointer at position 0; write
;; additionally treats its position-3 value slot as a pointer position when
;; its position-1 type can carry a pointer (:pointer/:void*/:iptr/:uptr).
;; alloc, sizeof, load-library, loaded?, null?, and string->ptr take no
;; provenance-bearing pointer argument. null? accepts a numeric pointer-shaped
;; value but only truncates and compares it with zero; it never dereferences or
;; reaches the OS.
(def ^:private native-operation-pointer-positions
  {:load-library #{}
   :loaded? #{}
   :alloc #{}
   :free #{0}
   :read #{0}
   :write #{0}
   :sizeof #{}
   :null? #{}
   :read-bytes #{0}
   :write-bytes #{0}
   :read-array #{0}
   :read-array! #{0}
   :write-array #{0}
   :ptr->string #{0}
   :string->ptr #{}})

(defn- pointer-argument-positions
  "Returns the exact set of argument-vector positions this descriptor passes a
  live pointer at. A :foreign-function descriptor derives its positions from
  :argument-types (every :pointer/:void*/:iptr/:uptr position); a
  :native-operation descriptor uses the fixed current-contract table above,
  adding position 3 for :write only when its position-1 type argument can carry
  a pointer."
  [descriptor]
  (case (:kind descriptor)
    :foreign-function
    (into #{}
          (keep-indexed
           (fn [i argument-type]
             (when (pointer-capable-argument-type? argument-type) i)))
          (:argument-types descriptor))

    :native-operation
    (let [operation (:operation descriptor)
          base (get native-operation-pointer-positions operation #{})]
      (if (and (= :write operation)
               (pointer-capable-argument-type?
                (get (:arguments descriptor) 1)))
        (conj base 3)
        base))))

(defn- validate-hybrid-classification! [descriptor decoded]
  (let [result (:value decoded)
        primary (primary-handler-result descriptor result)
        fixed-native-pointer?
        (and (= :native-operation (:kind descriptor))
             (contains? pointer-producing-native-operations
                        (:operation descriptor)))]
    ;; substitute asserts that a value is not a model-owned resource. Enforce
    ;; that assertion for the resource-producing signatures the generic ABI can
    ;; identify. The fixed native pointer producers permit only nil/zero as a
    ;; non-resource result. Pointer-typed reads/foreign calls may additionally
    ;; use negative API failure sentinels. Every other numeric pointer must
    ;; enter the provenance ledger.
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
        (when (and (= :native-operation (:kind descriptor))
                   (= :alloc (:operation descriptor)))
          (first (:arguments descriptor)))]
    (if (and (integer? candidate) (pos? candidate)) candidate 1)))

(defn- additional-ledger-entries
  "Validates and shapes zero or more with-additional-resources additions into
  ledger entries. Throws on the first malformed resource, before any entry --
  primary or additional -- reaches the ledger. Each entry's domain is :opaque:
  an addition carries no ABI type of its own (e.g. POSIX pipe's output file
  descriptors), so it stays conservatively checked against every argument
  position rather than only exact pointer-bearing ones."
  [descriptor handler-key additional-resources]
  (let [additional-resources (or additional-resources [])]
    (validate-additional-resources! additional-resources)
    (mapv
     (fn [resource]
       {:base (:base resource)
        :span (:span resource)
        :handler-key handler-key
        :descriptor descriptor
        :domain :opaque})
     additional-resources)))

(defn- register-modeled-resource!
  "Validates the primary modeled-resource result (if any) and every
  with-additional-resources addition, then appends every resulting ledger
  entry in one atomic swap!. A malformed primary or additional resource throws
  before that swap!, leaving the ledger completely unchanged.

  The primary entry's :domain is :pointer when pointer-producing-descriptor?
  identifies this exact call as returning a live pointer (alloc,
  string->ptr, a pointer-typed read, or a foreign call with
  a :pointer/:void* return type); otherwise it is :opaque, covering a numeric
  handle the ABI types cannot identify as a pointer (e.g. a scalar handle
  returned under :int/:uptr). A :pointer resource is later checked only
  against exact pointer-bearing argument positions; an :opaque resource
  remains checked against every position, unchanged from prior conservative
  behavior."
  [resource-ledger descriptor handler-key decoded]
  (let [primary-entry
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
            {:base base
             :span span
             :handler-key handler-key
             :descriptor descriptor
             :domain (if (pointer-producing-descriptor? descriptor)
                       :pointer
                       :opaque)}))
        additional-entries
        (additional-ledger-entries
         descriptor handler-key (:additional-resources decoded))
        entries (into (if primary-entry [primary-entry] [])
                      additional-entries)]
    (when (seq entries)
      (swap! resource-ledger into entries)))
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

(defn- resource-checked-at-position? [resource pointer-positions argument-index]
  (or (= :opaque (:domain resource))
      (contains? pointer-positions argument-index)))

(defn- modeled-resource-hit
  "Returns the first live ledger entry a descriptor's argument aliases, else
  nil. A :pointer-domain resource is checked only at this exact descriptor's
  pointer-bearing argument positions (see pointer-argument-positions), so an
  ordinary scalar argument -- a length, size, or status code -- that
  numerically coincides with a live fake pointer does not block an unrelated
  call. An :opaque-domain resource (a numeric handle the ABI types cannot
  identify as a pointer) remains checked against every argument position,
  exactly as before this distinction existed."
  [resource-ledger descriptor]
  ;; null? is a pure numeric predicate. Even opaque modeled resources, which
  ;; are conservatively checked at every ordinary argument position, may pass
  ;; through its native implementation safely because it cannot dereference
  ;; the value or perform an OS operation.
  (when-not (and (= :native-operation (:kind descriptor))
                 (= :null? (:operation descriptor)))
    (let [pointer-positions (pointer-argument-positions descriptor)]
      (some
       identity
       (map-indexed
        (fn [argument-index argument]
          (when-let [native-argument (native-truncated-number argument)]
            (when-let [resource
                       (some
                        (fn [{:keys [base span] :as resource}]
                          (when (and (resource-checked-at-position?
                                      resource pointer-positions argument-index)
                                     (<= base native-argument)
                                     (< native-argument (+ base span)))
                            resource))
                        @resource-ledger)]
              {:argument-index argument-index
               :argument argument
               :native-argument native-argument
               :resource resource})))
        (:arguments descriptor))))))

(defn- record-arrival! [effect-trace-log entry]
  "Atomically appends one route decision in interception-arrival order and
  returns its index. Handler or native completion order cannot reorder this
  evidence."
  (let [after (swap! effect-trace-log conj entry)]
    (dec (count after))))

(defn- finalize-arrival!
  "Replaces a previously reserved arrival entry in place at index, preserving
  its interception-arrival position. Used only when the final route decision
  for an already-reserved call is not known until after its handler has run,
  e.g. an explicit selected native proceed or the provenance guard blocking
  one."
  [effect-trace-log index entry]
  (swap! effect-trace-log assoc index entry)
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
  "Returns the established hermetic FFI controller. It is arity 2
  (descriptor proceed) to match the unified composite FFI callback contract;
  hermetic routing ignores the proceed continuation and never invokes it. It
  validates every incoming descriptor against the exact current shape, records
  its handler/blocked decision in arrival order before execution, and
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
  (fn ffi-controller [descriptor proceed]
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
  and otherwise invokes proceed only when no model-owned resource aliases this
  descriptor's live arguments: a :pointer-domain resource is checked only at
  this exact call's pointer-bearing argument positions (see
  pointer-argument-positions), while an :opaque-domain resource -- a numeric
  handle the ABI types cannot identify as a pointer -- is checked against
  every argument position. A dispatched hybrid handler may itself return
  proceed to explicitly select that same native branch for
  its exact call; the modeled-resource provenance guard still runs first, and
  the arrival-order effect-trace entry reserved for the handler dispatch is
  finalized in place as :route :native (or :blocked, if the guard fires)
  rather than :route :handler, retaining the selecting handler's identity.
  Native exceptions propagate with ordinary application semantics and are
  deliberately not controller errors. Policy, handler, and provenance failures
  remain latched fail closed."
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
                    arrival-index
                    (record-arrival!
                     effect-trace-log
                     {:mode :hybrid :route :handler
                      :handler-key key :descriptor descriptor})
                    raw-result (if (some? handler-fn)
                                 (handler-fn descriptor)
                                 nil)
                    decoded (decode-handler-result!
                             :hybrid descriptor handler-fn raw-result)]
                (if (= :proceed (:kind decoded))
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
                      (finalize-arrival!
                       effect-trace-log arrival-index
                       {:mode :hybrid :route :blocked
                        :reason :modeled-resource-native-fallback
                        :handler-key key :descriptor descriptor})
                      (record-ffi-error!
                       state descriptor :modeled-resource-native-fallback error)
                      (vreset! latched? true)
                      (throw error))
                    (do
                      (finalize-arrival!
                       effect-trace-log arrival-index
                       {:mode :hybrid :route :native
                        :handler-key key :descriptor descriptor})
                      (vreset! proceeding? true)
                      (proceed)))
                  (let [result (validate-capture-result!
                                descriptor (:value decoded))
                        decoded (validate-hybrid-classification!
                                 descriptor (assoc decoded :value result))]
                    (register-modeled-resource!
                     resource-ledger descriptor key decoded)
                    result)))
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

(defn- invalid-clock-descriptor! [reason descriptor]
  (throw
   (ex-info
    "The sim runtime received a malformed or unknown clock descriptor"
    {:type :jolt.sim.runtime/invalid-clock-descriptor
     :reason reason
     :descriptor descriptor})))

(defn- validate-clock-descriptor! [descriptor]
  ;; This exact two-entry map is the only valid v1 descriptor. Clock reads can
  ;; be hot in scheduler and poll loops, so the valid path uses allocation-free
  ;; map equality; detailed diagnostics remain on the exceptional slow path.
  (if (= mono-nanos-clock-descriptor descriptor)
    descriptor
    (do
      (when-not (map? descriptor)
        (invalid-clock-descriptor! :not-a-map descriptor))
      (when-not (= clock-descriptor-keys (set (keys descriptor)))
        (invalid-clock-descriptor! :wrong-keys descriptor))
      (when-not (= :clock (:kind descriptor))
        (invalid-clock-descriptor! :wrong-kind descriptor))
      (when-not (contains? clock-operations (:operation descriptor))
        (invalid-clock-descriptor! :unknown-operation descriptor))
      ;; Defensive fallback if the public contract gains a second exact spelling
      ;; without updating this fast-path constant.
      (invalid-clock-descriptor! :unsupported-descriptor descriptor))))

(defn- default-clock-controller
  "Returns the pass-through clock controller installed when run-controlled is
  given no explicit :clock. It is arity 2 (descriptor proceed) and proceeds
  every intercepted clock operation, so real OS monotonic time remains
  available to ordinary application code under the default hermetic run."
  []
  (fn default-clock [_descriptor proceed]
    (proceed)))

(defn- validate-clock-controller! [clock-controller]
  (when-not (fn? clock-controller)
    (throw
     (ex-info
      "run-controlled :clock must be a two-argument controller function"
      {:type :jolt.sim.runtime/invalid-config
       :clock clock-controller})))
  clock-controller)

(defn- make-clock-controller [clock-controller]
  (fn checked-clock-controller [descriptor proceed]
    (validate-clock-descriptor! descriptor)
    (clock-controller descriptor proceed)))

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
  restore, false when ownership or a callback remains at the deadline.

  Deadlines use the resolved private supervisor-mono-nanos, never the
  installed clock hook, so a frozen virtual clock still times out."
  [state timeout-ms supervisor-mono-nanos]
  (let [deadline (+ (supervisor-mono-nanos) (* timeout-ms 1000000))]
    (loop []
      (let [snap @state]
        (if (drained? snap)
          true
          (if (<= deadline (supervisor-mono-nanos))
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

    ;; Five-element capture-disabled configuration shorthand. This is a current
    ;; spelling convenience, not an older descriptor contract.
    (and (vector? key)
         (= 5 (count key))
         (= :foreign-function (nth key 0))
         (string? (nth key 1))
         (valid-argument-types? (nth key 2))
         (valid-argument-type? (nth key 3))
         (boolean? (nth key 4)))
    nil

    ;; Six-element capture-explicit shorthand. varargs-after defaults to nil.
    (and (vector? key)
         (= 6 (count key))
         (= :foreign-function (nth key 0))
         (string? (nth key 1))
         (valid-argument-types? (nth key 2))
         (valid-argument-type? (nth key 3))
         (boolean? (nth key 4))
         (boolean? (nth key 5)))
    nil

    ;; Canonical seven-element :foreign-function key. The boundary must be nil
    ;; or a positive integer no greater than the argument-type count.
    (and (vector? key)
         (= 7 (count key))
         (= :foreign-function (nth key 0))
         (string? (nth key 1))
         (valid-argument-types? (nth key 2))
         (valid-argument-type? (nth key 3))
         (boolean? (nth key 4))
         (boolean? (nth key 5))
         (valid-varargs-boundary? (nth key 2) (nth key 6)))
    nil

    :else
    (throw
     (ex-info
      "run-controlled :ffi-handlers entry has a malformed key"
      {:type :jolt.sim.runtime/invalid-config
       :handler-key key}))))

(defn- canonical-handler-key
  "Canonicalizes a validated :ffi-handlers key to the seven-element internal
  [:foreign-function symbol argument-types return-type blocking? capture?
  varargs-after] identity. The five-element shorthand has no capture? term
  and canonicalizes to false/nil; the six-element shorthand has no boundary
  and canonicalizes to nil; every other validated key (native-operation, or
  an already seven-element foreign-function key) passes through unchanged."
  [key]
  (let [n (count key)]
    (cond
      (and (= n 5) (= :foreign-function (nth key 0)))
      (conj (conj key false) nil)

      (and (= n 6) (= :foreign-function (nth key 0)))
      (conj key nil)

      :else key)))

(defn- validate-ffi-handlers!
  "Validates every :ffi-handlers key/value pair, then returns the map
  canonicalized to seven-element :foreign-function keys. Rejects a config that
  supplies any two of the five-element shorthand, its equivalent six-element
  capture?-false key, or the equivalent seven-element nil-boundary key for the
  same signature, since that config cannot express which handler applies
  without silently overwriting the other."
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
        "run-controlled :ffi-handlers supplies two spellings that canonicalize to the same seven-element key for one signature"
        {:type :jolt.sim.runtime/invalid-config
         :ambiguous-keys (vec (keys ambiguous))})))
    (into {}
          (map (fn [[key value]] [(canonical-handler-key key) value]))
          handlers)))

(defn normalize-ffi-handlers
  "Validates and canonicalizes an FFI handler map using run-controlled's exact
  current contract. A five-element foreign-function shorthand defaults capture
  to false; a six-element shorthand preserves its explicit capture Boolean.
  Both default varargs-after to nil and become canonical seven-element keys; an
  exact seven-element key carries its validated nil/positive boundary. This pure
  helper is public so extension and handler-pack tooling cannot drift from
  runtime validation."
  [handlers]
  (validate-ffi-handlers! handlers))

(defn- restore-controllers!
  "Restores the single composite controller token. The session becomes reusable
  only after the exact-token restore succeeds; a failed restore poisons the
  shared session rather than leaving controller ownership unknown."
  [ops token]
  (try
    ((:restore-controller! ops) token)
    (reset! session-state :idle)
    (catch :default error
      (reset! session-state :poisoned)
      (throw
       (ex-info
        "jolt.sim.runtime could not restore its controller ownership"
        {:type :jolt.sim.runtime/controller-cleanup-error
         :errors (trace/normalize-error [error])})))))

(defn- validate-run-arguments! [config thunk]
  (when-not (map? config)
    (throw
     (ex-info "run-controlled config must be a map"
              {:type :jolt.sim.runtime/invalid-config})))
  ;; Validate pure config before resolving the optional runtime capability.
  (let [unknown-keys
        (vec (sort (remove #{:on-event :ffi-handlers :ffi-mode
                             :drain-timeout-ms :future-schedule :clock}
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
  (when (contains? config :clock)
    (validate-clock-controller! (:clock config)))
  (when (contains? config :future-schedule)
    (future-schedule/validate-schedule! (:future-schedule config))))

(defn run-controlled
  "Runs thunk under the simulation controller described by config.

   Resolves the exact current controller contract, throwing
   :jolt.sim.runtime/abi-unavailable or /abi-incompatible otherwise. Atomically
   claims the single session, clears stale errors, installs one composite
   controller map (future, FFI, and clock callbacks) via a single
   install-controller! call capturing one restore token, and runs the unchanged
   thunk. The FFI callback is always arity 2 (descriptor proceed).
   :ffi-mode defaults to :hermetic and ignores proceed while substituting
   registered handlers and blocking unhandled effects. :observe/:hybrid retain
   the safe routing controller; observe proceeds every call, while hybrid
   proceeds misses only after its modeled-resource guard. A registered hybrid
   handler may return jolt.sim.runtime/proceed to select that native branch
   explicitly for its exact call (still subject to the same guard), and may
   return jolt.sim.runtime/with-additional-resources to atomically register
   extra modeled resources alongside its primary classified result. Restoration
   is one restore-controller! call over the single composite token.

   An optional :clock config supplies an arity-2 (descriptor proceed) clock
   controller; when absent a pass-through clock proceeds every intercepted
   operation so real OS monotonic time remains available. Drain deadlines use
   the resolved private supervisor-mono-nanos, never the installed clock, so a
   frozen virtual clock still times out.

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
   the composite controller stays installed and the session is poisoned rather
   than restored unsafely.

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
        ffi-mode (get config :ffi-mode :hermetic)
        supervisor-mono-nanos (:supervisor-mono-nanos ops)]
    (let [ffi-handlers (if (contains? config :ffi-handlers)
                         (validate-ffi-handlers! (:ffi-handlers config))
                         {})
          schedule (when-let [future-schedule (:future-schedule config)]
                     (future-schedule/scheduler future-schedule on-event))
          effective-on-event (if schedule (:on-event schedule) on-event)
          clock-controller
          (make-clock-controller
           (or (:clock config) (default-clock-controller)))
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
          ;; One composite callback map installed by a single
          ;; install-controller! call. The exact clock descriptor was already
          ;; validated as part of capabilities before this point, so the
          ;; installed clock controller is safe to invoke during user code.
          composite {:future controller :ffi ffi-controller :clock clock-controller}
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
      (let [token (volatile! nil)
            installed? (volatile! false)]
        (try
          ((:clear-controller-errors! ops))
          ;; One atomic composite install captures the single restore token.
          (vreset! token ((:install-controller! ops) composite))
          (vreset! installed? true)
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
                ;; The deadline uses the supervisor clock, never the installed
                ;; virtual clock, so a frozen clock still times out.
                _drain-attempt
                (drain-owned! state drain-timeout-ms supervisor-mono-nanos)
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
            ;; remains in flight; the composite controller stays installed and
            ;; the session is poisoned instead.
            (try
              (close-state! state)
              (finally
                (if-not @installed?
                  ;; The exact host install is atomic: a thrown install publishes
                  ;; no controller, so there is no token to restore and the
                  ;; process remains reusable.
                  (reset! session-state :idle)
                  (let [final @state]
                    (if (not (drained? final))
                      (reset! session-state :poisoned)
                      (restore-controllers! ops @token))))))))))))

(defn- invalid-defsim! [name reason data]
  (throw
   (ex-info
    "defsim declaration is malformed"
    (merge {:type :jolt.sim.runtime/invalid-defsim
            :scenario name
            :reason reason}
           data))))

(defmacro defsim
  "Defines a marked scenario named name that runs body under run-controlled with
  config. The body is ordinary Jolt code; defsim only installs the controller,
  event capture, and cleanup around it.

  Two declaration forms are accepted: `(defsim name config & body)` -- the
  original form -- and `(defsim name [input] config & body)`, which binds one
  extra symbol to a caller-supplied input value for the run.

  The generated function always has three arities. `([] ...)` preserves the
  original no-argument contract. `([runtime-overrides] ...)` requires a map
  and merges it over the declared config before the run, allowing an external
  harness to supply one `:future-schedule` without rewriting or wrapping the
  application body. `([runtime-overrides input] ...)` additionally accepts a
  scenario input value: a scenario declared with the `[input]` binding form
  runs its body with that symbol bound to the given value; a scenario declared
  with the original form has no input to bind and rejects any non-nil input
  with a typed `:jolt.sim.runtime/scenario-rejects-input` error instead of
  silently discarding it. The zero- and one-argument arities always pass nil
  input, so existing calls are unaffected by this addition.

  The var carries `:jolt.sim/scenario true` and a boolean
  `:jolt.sim/accepts-input` metadata marker so a process worker can fail closed
  before invocation instead of trusting an application-thrown exception tag.

  The expansion references the fully qualified run-controlled, so the scenario
  is callable from any namespace. Not coupled to clojure.test."
  [name & args]
  (when (empty? args)
    (invalid-defsim! name :missing-config {}))
  (let [has-input? (vector? (first args))]
    (when (and has-input? (< (count args) 2))
      (invalid-defsim! name :missing-config {:binding (first args)}))
    (when has-input?
      (let [binding (first args)]
        (when-not (= 1 (count binding))
          (invalid-defsim! name :binding-arity {:binding binding}))
        (when-not (and (symbol? (first binding))
                       (nil? (namespace (first binding)))
                       (not= '& (first binding)))
          (invalid-defsim! name :binding-not-a-simple-symbol
                           {:binding binding}))))
    (let [config (if has-input? (second args) (first args))
          body (if has-input? (nnext args) (next args))
          input-binding-sym (when has-input? (first (first args)))
          scenario-name
          (with-meta name
            (assoc (meta name)
                   :jolt.sim/scenario true
                   :jolt.sim/accepts-input has-input?))
          overrides-sym (gensym "runtime-overrides")
          input-sym (gensym "input")
          base-config-sym (gensym "base-config")
          run-form
          `(let [~base-config-sym ~config]
             ;; Keep run-controlled authoritative for malformed declared
             ;; config. Passing it through unchanged preserves the original
             ;; no-arg error.
             (jolt.sim.runtime/run-controlled
              (if (map? ~base-config-sym)
                (merge ~base-config-sym ~overrides-sym)
                ~base-config-sym)
              (fn [] ~@body)))]
      `(defn ~scenario-name
         ([]
          (~scenario-name {} nil))
         ([~overrides-sym]
          (~scenario-name ~overrides-sym nil))
         ([~overrides-sym ~input-sym]
          (when-not (map? ~overrides-sym)
            (throw
             (ex-info
              "defsim runtime overrides must be a map"
              {:type :jolt.sim.runtime/invalid-config
               :scenario '~name
               :runtime-overrides ~overrides-sym})))
          ~(if has-input?
             `(let [~input-binding-sym ~input-sym]
                ~run-form)
             `(do
                (when (some? ~input-sym)
                  (throw
                   (ex-info
                    "defsim scenario does not declare an input binding and rejects non-nil input"
                    {:type :jolt.sim.runtime/scenario-rejects-input
                     :scenario '~name
                     :input ~input-sym})))
                ~run-form)))))))
