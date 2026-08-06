(ns jolt.sim.pack-registry
  "Immutable trusted pack registry for experiment vocabulary discovery.

  Connection and check packs are ordinary immutable values constructed by
  trusted application or library code through connection-pack and check-pack.
  A registry is an explicit immutable value composed at one call site. This
  namespace never mutates process-global state, scans namespaces, requires or
  resolves vars, or evaluates data; executable entries (compiler and checker
  functions) must already be in-process functions supplied by trusted code.

  All discoverable descriptor data (documentation, config schema, template,
  mode parameter schemas, fault catalog, required observations) is recursively
  data-only, so a descriptor can be inspected, rendered, and compared without
  ever executing it. Registry composition rejects duplicate [kind id]
  identities, even identical values; later registries never silently override
  earlier ones.

  Compilation entry points resolve a pack, validate the data-only request,
  invoke the trusted function, and revalidate the returned binding's explicit
  type marker and identity. Semantic assembly of bindings into worlds,
  handlers, or monitors belongs to the later experiment compiler, not here.")

(def ^:private type-key :jolt.sim.pack-registry/type)

(def ^:private pack-kinds #{:connection :check})

;; The workbench core owns the mode vocabulary; packs only declare support.
(def ^:private mode-names
  #{:pass-through :record :simulate :hybrid :observational})

(def ^:private connection-descriptor-keys
  #{:id :doc :config-schema :template :modes :faults :compile})

(def ^:private check-descriptor-keys
  #{:id :doc :config-schema :template :observations :check})

(def ^:private connection-binding-keys
  #{:jolt.sim.pack-registry/type :pack-id :mode :fields})

(def ^:private check-binding-keys
  #{:jolt.sim.pack-registry/type :pack-id :fields})

(def ^:private registry-keys
  #{:jolt.sim.pack-registry/type :packs})

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

;; ---- recursively data-only discovery data --------------------------------

(defn- scalar-data? [value]
  (or (nil? value) (boolean? value) (string? value) (keyword? value)
      (symbol? value) (number? value) (char? value)))

(defn- non-data-path
  "Returns nil when value is recursively data-only, else the path of the
  first non-data value. Functions, vars, atoms, host objects, metadata, and
  lazy or otherwise executable sequences are never data. Map keys are checked
  too; structural path markers never retain the rejected value."
  [value]
  (letfn [(walk [path v]
            (cond
              (some? (meta v)) (conj path ::metadata)
              (scalar-data? v) nil
              (map? v)
              (loop [entries (seq v)]
                (when entries
                  (let [[k entry] (first entries)]
                    (or (walk (conj path ::map-key) k)
                        (walk (conj path k) entry)
                        (recur (next entries))))))
              (or (vector? v) (list? v))
              (loop [index 0 entries (seq v)]
                (when entries
                  (or (walk (conj path index) (first entries))
                      (recur (inc index) (next entries)))))
              (set? v)
              (loop [entries (seq v)]
                (when entries
                  (or (walk (conj path ::set-element) (first entries))
                      (recur (next entries)))))
              :else path))]
    (walk [] value)))

;; ---- descriptor validation ------------------------------------------------

(defn- pack-id? [id]
  (and (keyword? id)
       (let [ns (namespace id)]
         (and (string? ns) (pos? (count ns))))
       (some? (re-matches #".+-v[1-9][0-9]*" (name id)))))

(defn- validate-id! [kind id]
  (when-not (pack-id? id)
    (fail! :jolt.sim.pack-registry/invalid-pack-id
           "pack id must be a versioned namespaced keyword such as :http/form-session-v1"
           {:kind kind :pack-id id})))

(defn- validate-data-field! [kind id field value]
  (when-let [path (non-data-path value)]
    (fail! :jolt.sim.pack-registry/invalid-descriptor
           "discoverable descriptor data must be recursively data-only"
           {:kind kind :pack-id id :field field :path path})))

(defn- validate-descriptor-shape! [kind descriptor required-keys]
  (when-not (map? descriptor)
    (fail! :jolt.sim.pack-registry/invalid-descriptor
           "a pack descriptor is a map"
           {:kind kind :value descriptor}))
  (when (some? (meta descriptor))
    (fail! :jolt.sim.pack-registry/invalid-descriptor
           "a pack descriptor must not carry metadata"
           {:kind kind :pack-id (:id descriptor)
            :field ::descriptor :path [::metadata]}))
  (when (contains? descriptor type-key)
    (fail! :jolt.sim.pack-registry/invalid-descriptor
           "descriptor input must not carry the registry type marker"
           {:kind kind :pack-id (:id descriptor)}))
  (when-not (= required-keys (set (keys descriptor)))
    (fail! :jolt.sim.pack-registry/invalid-descriptor
           "descriptor keys must be exactly the required set"
           {:kind kind
            :pack-id (:id descriptor)
            :expected required-keys
            :actual (set (keys descriptor))}))
  (let [id (:id descriptor)]
    (validate-id! kind id)
    (when-not (string? (:doc descriptor))
      (fail! :jolt.sim.pack-registry/invalid-descriptor
             "descriptor :doc must be a string"
             {:kind kind :pack-id id :field :doc}))
    (validate-data-field! kind id :config-schema (:config-schema descriptor))
    (validate-data-field! kind id :template (:template descriptor))
    id))

(defn- validate-modes! [kind id modes]
  (when-not (and (map? modes) (seq modes))
    (fail! :jolt.sim.pack-registry/invalid-descriptor
           "a connection pack declares at least one supported mode"
           {:kind kind :pack-id id :field :modes}))
  (validate-data-field! kind id :modes modes)
  (doseq [[mode entry] modes]
    (when-not (contains? mode-names mode)
      (fail! :jolt.sim.pack-registry/invalid-descriptor
             "mode names belong to the workbench core vocabulary"
             {:kind kind :pack-id id :field :modes
              :mode mode :supported mode-names}))
    (when-not (and (map? entry) (= #{:params-schema} (set (keys entry))))
      (fail! :jolt.sim.pack-registry/invalid-descriptor
             "a mode entry is exactly {:params-schema data}"
             {:kind kind :pack-id id :field :modes :mode mode}))
    (validate-data-field! kind id :modes entry)))

(defn connection-pack
  "Validates a trusted connection pack descriptor and returns the pack value.

  The descriptor keys are exactly :id, :doc, :config-schema, :template,
  :modes, :faults, and :compile. :compile must be an in-process function;
  every other field must be recursively data-only. A data-only map, however
  it was produced, can therefore never install an executable callback."
  [descriptor]
  (validate-descriptor-shape! :connection descriptor connection-descriptor-keys)
  (let [id (:id descriptor)]
    (validate-modes! :connection id (:modes descriptor))
    (validate-data-field! :connection id :faults (:faults descriptor))
    (when-not (fn? (:compile descriptor))
      (fail! :jolt.sim.pack-registry/invalid-descriptor
             "connection :compile must be an in-process function"
             {:kind :connection :pack-id id :field :compile}))
    (assoc descriptor type-key ::connection-pack)))

(defn check-pack
  "Validates a trusted check pack descriptor and returns the pack value.

  The descriptor keys are exactly :id, :doc, :config-schema, :template,
  :observations, and :check. :check must be an in-process function; every
  other field must be recursively data-only."
  [descriptor]
  (validate-descriptor-shape! :check descriptor check-descriptor-keys)
  (let [id (:id descriptor)]
    (validate-data-field! :check id :observations (:observations descriptor))
    (when-not (fn? (:check descriptor))
      (fail! :jolt.sim.pack-registry/invalid-descriptor
             "check :check must be an in-process function"
             {:kind :check :pack-id id :field :check}))
    (assoc descriptor type-key ::check-pack)))

;; ---- registry composition -------------------------------------------------

(defn- pack-kind [pack]
  (case (get pack type-key)
    ::connection-pack :connection
    ::check-pack :check
    nil))

(defn- ensure-pack
  "Reconstructs a pack value through its constructor so an assoc'd or
  deserialized map cannot smuggle an invalid descriptor into a registry."
  [value]
  (when-not (and (map? value) (some? (pack-kind value)))
    (fail! :jolt.sim.pack-registry/not-a-pack
           "registry entries are values returned by connection-pack or check-pack"
           {:value value}))
  (let [descriptor (dissoc value type-key)]
    (if (= :connection (pack-kind value))
      (connection-pack descriptor)
      (check-pack descriptor))))

(defn- add-pack [packs value]
  (let [pack (ensure-pack value)
        kind (pack-kind pack)
        id (:id pack)
        key [kind id]]
    (when (contains? packs key)
      (fail! :jolt.sim.pack-registry/duplicate-pack
             "two packs share the same kind and id"
             {:kind kind :pack-id id}))
    (assoc packs key pack)))

(defn registry
  "Composes zero or more pack collections into one immutable registry value.

  A typical call is (registry default-packs library-packs app-packs). Nil
  sources are ignored so optional integrations need no empty-map branch.
  Duplicate [kind id] identities always fail, even for identical values; the
  same id may coexist under :connection and :check and is then resolved with
  an explicit kind-qualified reference."
  [& sources]
  {type-key ::registry
   :packs (reduce
           (fn [packs source]
             (cond
               (nil? source) packs
               (and (coll? source) (not (map? source)))
               (reduce add-pack packs source)
               :else (fail! :jolt.sim.pack-registry/invalid-registry-source
                            "registry sources are nil or collections of packs"
                            {:source source})))
           {}
           sources)})

(defn- ensure-registry [value]
  (when-not (and (map? value)
                 (= registry-keys (set (keys value)))
                 (= ::registry (get value type-key))
                 (map? (:packs value)))
    (fail! :jolt.sim.pack-registry/not-a-registry
           "value is not a registry built by registry"
           {:value value}))
  value)

(defn- ensure-entry [key pack]
  (when-not (and (vector? key) (= 2 (count key))
                 (contains? pack-kinds (nth key 0)))
    (fail! :jolt.sim.pack-registry/not-a-registry
           "registry keys are [kind id] pairs"
           {:key key}))
  (let [rebuilt (ensure-pack pack)]
    (when-not (and (= (nth key 0) (pack-kind rebuilt))
                   (= (nth key 1) (:id rebuilt)))
      (fail! :jolt.sim.pack-registry/not-a-registry
             "registry entry identity does not match its key"
             {:key key :pack-id (:id rebuilt)}))
    rebuilt))

;; ---- resolution -----------------------------------------------------------

(defn- normalize-ref [ref]
  (if (or (keyword? ref)
          (and (vector? ref) (= 2 (count ref))
               (contains? pack-kinds (nth ref 0))
               (keyword? (nth ref 1))))
    ref
    (fail! :jolt.sim.pack-registry/invalid-pack-ref
           "a pack reference is a pack id keyword or a [kind id] pair"
           {:ref ref})))

(defn resolve-pack
  "Resolves a pack reference against registry and returns the trusted pack
  value, including its executable entries, for compilation or invocation.

  A bare id resolves only when exactly one kind registers it; kind-qualified
  [kind id] references are unambiguous. Unknown and ambiguous references fail
  with stable ex-data."
  [registry ref]
  (ensure-registry registry)
  (let [ref (normalize-ref ref)
        packs (:packs registry)]
    (if (keyword? ref)
      (let [found (into []
                        (keep (fn [kind]
                                (let [key [kind ref]]
                                  (when (contains? packs key)
                                    [kind (ensure-entry key (get packs key))]))))
                        [:connection :check])]
        (case (count found)
          0 (fail! :jolt.sim.pack-registry/unknown-pack
                   "no pack registered with this id"
                   {:ref ref})
          1 (nth (first found) 1)
          (fail! :jolt.sim.pack-registry/ambiguous-pack
                 "pack id is registered under more than one kind; qualify with [kind id]"
                 {:ref ref :kinds (mapv first found)})))
      (if (contains? packs ref)
        (ensure-entry ref (get packs ref))
        (fail! :jolt.sim.pack-registry/unknown-pack
               "no pack registered for this [kind id] reference"
               {:ref ref})))))

;; ---- data-only discovery ----------------------------------------------------

(defn describe
  "Returns the recursively data-only projection of one pack: every descriptor
  field except the executable entries, plus its :kind."
  [registry ref]
  (let [pack (resolve-pack registry ref)]
    (-> pack
        (dissoc type-key :compile :check)
        (assoc :kind (pack-kind pack)))))

(defn template
  "Returns the pack's data-only configuration template."
  [registry ref]
  (:template (resolve-pack registry ref)))

(defn- connection-only [query pack]
  (when-not (= :connection (pack-kind pack))
    (fail! :jolt.sim.pack-registry/unsupported-query
           "this query is defined on connection packs"
           {:query query :kind (pack-kind pack) :pack-id (:id pack)}))
  pack)

(defn modes
  "Returns the connection pack's data-only mode map of mode to
  {:params-schema data}."
  [registry ref]
  (:modes (connection-only :modes (resolve-pack registry ref))))

(defn faults
  "Returns the connection pack's data-only fault catalog."
  [registry ref]
  (:faults (connection-only :faults (resolve-pack registry ref))))

;; ---- trusted compilation and binding revalidation ---------------------------

(defn connection-binding
  "Constructor for the closed result a trusted connection compiler returns.
  fields is opaque to this namespace; its semantics belong to the experiment
  compiler."
  [pack-id mode fields]
  (when-not (pack-id? pack-id)
    (fail! :jolt.sim.pack-registry/invalid-binding
           "connection binding pack-id must be a versioned pack id"
           {:pack-id pack-id}))
  (when-not (keyword? mode)
    (fail! :jolt.sim.pack-registry/invalid-binding
           "connection binding mode must be a keyword"
           {:pack-id pack-id :mode mode}))
  (when-not (map? fields)
    (fail! :jolt.sim.pack-registry/invalid-binding
           "connection binding fields must be a map"
           {:pack-id pack-id :mode mode}))
  {type-key ::connection-binding
   :pack-id pack-id
   :mode mode
   :fields fields})

(defn validate-connection-binding!
  "Revalidates a connection binding envelope against the exact pack id and
  mode that were requested. Exact shape, type marker, and identity checks
  reject values that were assoc'd or deserialized outside the compiler
  contract. Returns value when valid."
  [value pack-id mode]
  (when-not (and (map? value)
                 (= connection-binding-keys (set (keys value)))
                 (= ::connection-binding (get value type-key))
                 (map? (:fields value)))
    (fail! :jolt.sim.pack-registry/invalid-binding
           "not a connection binding produced by connection-binding"
           {:pack-id pack-id :mode mode}))
  (when-not (= pack-id (:pack-id value))
    (fail! :jolt.sim.pack-registry/invalid-binding
           "connection binding pack-id does not match the request"
           {:expected pack-id :actual (:pack-id value)}))
  (when-not (= mode (:mode value))
    (fail! :jolt.sim.pack-registry/invalid-binding
           "connection binding mode does not match the request"
           {:expected mode :actual (:mode value)}))
  value)

(defn check-binding
  "Constructor for the closed result a trusted check binder returns."
  [pack-id fields]
  (when-not (pack-id? pack-id)
    (fail! :jolt.sim.pack-registry/invalid-binding
           "check binding pack-id must be a versioned pack id"
           {:pack-id pack-id}))
  (when-not (map? fields)
    (fail! :jolt.sim.pack-registry/invalid-binding
           "check binding fields must be a map"
           {:pack-id pack-id}))
  {type-key ::check-binding
   :pack-id pack-id
   :fields fields})

(defn validate-check-binding!
  "Revalidates a check binding envelope against the exact pack id that was
  requested. Returns value when valid."
  [value pack-id]
  (when-not (and (map? value)
                 (= check-binding-keys (set (keys value)))
                 (= ::check-binding (get value type-key))
                 (map? (:fields value)))
    (fail! :jolt.sim.pack-registry/invalid-binding
           "not a check binding produced by check-binding"
           {:pack-id pack-id}))
  (when-not (= pack-id (:pack-id value))
    (fail! :jolt.sim.pack-registry/invalid-binding
           "check binding pack-id does not match the request"
           {:expected pack-id :actual (:pack-id value)}))
  value)

(defn- validate-data-request-field! [type pack-id field value]
  (when-let [path (non-data-path value)]
    (fail! type
           "request data must be recursively data-only"
           {:pack-id pack-id :field field :path path})))

(defn compile-connection
  "Resolves a connection pack, validates the request, invokes its trusted
  compiler, and revalidates the returned connection binding.

  request is exactly {:mode keyword :config data :params data}. The mode
  must be one the pack declares; config and params come from experiment data
  and must be recursively data-only, so a manifest can never supply an
  executable callback. The compiler is invoked with {:id id :mode mode
  :config config :params params} and must answer with connection-binding."
  [registry ref request]
  (let [pack (connection-only :compile-connection (resolve-pack registry ref))
        id (:id pack)]
    (when-not (and (map? request)
                   (= #{:mode :config :params} (set (keys request))))
      (fail! :jolt.sim.pack-registry/invalid-request
             "a connection request is exactly {:mode ... :config ... :params ...}"
             {:pack-id id
              :actual (when (map? request) (set (keys request)))}))
    (when-not (keyword? (:mode request))
      (fail! :jolt.sim.pack-registry/invalid-request
             "connection request :mode must be a keyword"
             {:pack-id id :mode (:mode request)}))
    (when-not (contains? (:modes pack) (:mode request))
      (fail! :jolt.sim.pack-registry/unsupported-mode
             "the pack does not support the requested mode"
             {:pack-id id :mode (:mode request)
              :supported (set (keys (:modes pack)))}))
    (validate-data-request-field! :jolt.sim.pack-registry/invalid-request
                                  id :config (:config request))
    (validate-data-request-field! :jolt.sim.pack-registry/invalid-request
                                  id :params (:params request))
    (validate-connection-binding!
     ((:compile pack) {:id id
                       :mode (:mode request)
                       :config (:config request)
                       :params (:params request)})
     id
     (:mode request))))

(defn compile-check
  "Resolves a check pack, validates the request, invokes its trusted checker
  binder, and revalidates the returned check binding.

  request is exactly {:config data}; config must be recursively data-only.
  The binder is invoked with {:id id :config config} and must answer with
  check-binding."
  [registry ref request]
  (let [pack (resolve-pack registry ref)
        id (:id pack)]
    (when-not (= :check (pack-kind pack))
      (fail! :jolt.sim.pack-registry/unsupported-query
             "compile-check is defined on check packs"
             {:query :compile-check :kind (pack-kind pack) :pack-id id}))
    (when-not (and (map? request)
                   (= #{:config} (set (keys request))))
      (fail! :jolt.sim.pack-registry/invalid-request
             "a check request is exactly {:config ...}"
             {:pack-id id
              :actual (when (map? request) (set (keys request)))}))
    (validate-data-request-field! :jolt.sim.pack-registry/invalid-request
                                  id :config (:config request))
    (validate-check-binding!
     ((:check pack) {:id id :config (:config request)})
     id)))
