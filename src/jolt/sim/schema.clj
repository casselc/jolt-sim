(ns jolt.sim.schema
  "Fail-closed validation for the workbench's inspectable Malli subset.

  Pack descriptors retain ordinary Malli forms for discovery and Ripple UI
  projection. Trusted plan compilation uses this namespace to reject schemas
  with executable or ambient resolution semantics, compile a validator, and
  validate the exact submitted value without coercion. Failure ex-data keeps
  only stable coordinates and caller-supplied identity context; it never
  retains the submitted value or Malli's full explanation."
  (:require [malli.core :as m]))

(def ^:private type-key :jolt.sim.schema/type)
(def ^:private compiled-keys
  #{:jolt.sim.schema/type :form :declared-keys :validator :explainer})
(def ^:private supported-types
  #{:nil :boolean :int :double :string := :enum :maybe :or :tuple
    :vector :sequential :set :map-of :map})
(def ^:private context-keys #{:kind :pack-id :field :mode})

(defn- fail! [type message context detail]
  (throw (ex-info message (merge context detail {:type type}))))

(defn- normalize-context [context]
  (when-not (and (map? context)
                 (every? context-keys (keys context))
                 (every? keyword? (vals context)))
    (throw (ex-info "schema context contains only keyword identity fields"
                    {:type ::invalid-context})))
  context)

(defn- malli-compile-stage [context thunk]
  (try
    (thunk)
    (catch Throwable _
      (fail! ::invalid-schema "invalid Malli schema" context
             {:schema-path []}))))

(defn- reject-properties! [context path properties allowed]
  (doseq [[property _] properties]
    (when-not (contains? allowed property)
      (fail! ::unsupported-schema
             "schema property is outside the inspectable workbench subset"
             context
             {:schema-path (conj path :properties property)
              :property property}))))

(defn- validate-size-bounds! [context path properties]
  (reject-properties! context path properties #{:min :max})
  (let [minimum (when (contains? properties :min) (:min properties))
        maximum (when (contains? properties :max) (:max properties))]
    (when-not (and (or (nil? minimum)
                       (and (integer? minimum) (<= 0 minimum)))
                   (or (nil? maximum)
                       (and (integer? maximum) (<= 0 maximum)))
                   (or (nil? minimum) (nil? maximum) (<= minimum maximum)))
      (fail! ::unsupported-schema
             "collection bounds must be nonnegative integers with min <= max"
             context
             {:schema-path path}))))

(defn- finite-number? [value]
  (and (number? value)
       (= value value)
       (<= (- Double/MAX_VALUE) value Double/MAX_VALUE)))

(defn- validate-numeric-bounds! [context path properties integer-bounds?]
  (reject-properties! context path properties #{:min :max})
  (let [minimum (when (contains? properties :min) (:min properties))
        maximum (when (contains? properties :max) (:max properties))
        valid? (if integer-bounds?
                 #(and (integer? %)
                       (<= Long/MIN_VALUE % Long/MAX_VALUE))
                 finite-number?)]
    (when-not (and (or (nil? minimum) (valid? minimum))
                   (or (nil? maximum) (valid? maximum))
                   (or (nil? minimum) (nil? maximum) (<= minimum maximum)))
      (fail! ::unsupported-schema
             "numeric bounds must be finite compatible values with min <= max"
             context
             {:schema-path path}))))

(declare validate-ast!)

(defn- declared-map-keys [ast]
  (let [own (if (= :map (:type ast)) (set (keys (:keys ast))) #{})
        type (:type ast)
        descendants
        (cond
          (= :map type) (map :value (vals (:keys ast)))
          (= :map-of type) [(:key ast) (:value ast)]
          (contains? #{:maybe :vector :sequential :set} type) [(:child ast)]
          (contains? #{:or :tuple} type) (:children ast)
          :else [])]
    (reduce into own (map declared-map-keys descendants))))

(defn- validate-children! [context path ast]
  (doseq [[index child] (map-indexed vector (:children ast))]
    (validate-ast! context (conj path :children index) child)))

(defn- validate-map! [context path ast]
  (reject-properties! context path (:properties ast) #{:closed})
  (when-not (true? (get-in ast [:properties :closed]))
    (fail! ::unsupported-schema
           "workbench map schemas must be explicitly closed"
           context
           {:schema-path (conj path :properties :closed)}))
  (doseq [[key entry] (:keys ast)]
    (when (= key ::m/default)
      (fail! ::unsupported-schema
             "default Malli map entries are not supported"
             context
             {:schema-path (conj path :keys key)}))
    (let [entry-path (conj path :keys key)
          properties (:properties entry)
          optional (:optional properties)]
      (reject-properties! context entry-path properties #{:optional})
      (when-not (or (nil? optional) (boolean? optional))
        (fail! ::unsupported-schema
               "Malli map entry :optional must be boolean"
               context
               {:schema-path (conj entry-path :properties :optional)}))
      (validate-ast! context (conj entry-path :value) (:value entry)))))

(defn- validate-ast! [context path ast]
  (when (contains? ast :registry)
    (fail! ::unsupported-schema
           "Malli registries and references are not supported"
           context
           {:schema-path (conj path :registry)}))
  (let [type (:type ast)]
    (when-not (contains? supported-types type)
      (fail! ::unsupported-schema
             "schema type is outside the inspectable workbench subset"
             context
             {:schema-path path :schema-type type}))
    (cond
      (contains? #{:nil :boolean := :enum :maybe :or :tuple} type)
      (reject-properties! context path (:properties ast) #{})

      (= :int type)
      (validate-numeric-bounds! context path (:properties ast) true)

      (= :double type)
      (validate-numeric-bounds! context path (:properties ast) false)

      (contains? #{:string :vector :sequential :set :map-of} type)
      (validate-size-bounds! context path (:properties ast))

      (= :map type)
      (validate-map! context path ast))
    (case type
      :maybe (validate-ast! context (conj path :child) (:child ast))
      :map-of
      (do
        (validate-ast! context (conj path :key) (:key ast))
        (validate-ast! context (conj path :value) (:value ast)))
      (if (contains? #{:vector :sequential :set} type)
        (validate-ast! context (conj path :child) (:child ast))
        (when (contains? #{:or :tuple} type)
          (validate-children! context path ast))))))

(defn compile!
  "Compiles one data-only Malli form from the supported closed subset.

  context is merged into stable failure ex-data and should carry identities
  such as :pack-id, :field, and optionally :mode. The returned value is an
  opaque process-local validator; discovery continues to expose the raw form."
  [context form]
  (let [context (normalize-context context)
        compiled (malli-compile-stage context #(m/schema form))
        canonical-form (malli-compile-stage context #(m/form compiled))
        ast (malli-compile-stage context #(m/ast compiled))]
    (validate-ast! context [] ast)
    {type-key ::compiled
     :form canonical-form
     :declared-keys (declared-map-keys ast)
     :validator (malli-compile-stage context #(m/validator compiled))
     :explainer (malli-compile-stage context #(m/explainer compiled))}))

(defn validate!
  "Returns value unchanged when it conforms to compiled-schema.

  Invalid-value errors retain only schema/value coordinates, never value or
  Malli's complete explanation."
  [compiled-schema value context]
  (let [context (normalize-context context)]
    (when-not (and (map? compiled-schema)
                 (= compiled-keys (set (keys compiled-schema)))
                 (= ::compiled (get compiled-schema type-key))
                 (fn? (:validator compiled-schema))
                 (fn? (:explainer compiled-schema)))
      (fail! ::not-compiled "value is not a compiled workbench schema"
             context {}))
    (let [valid?
          (try
            ((:validator compiled-schema) value)
            (catch Throwable _
              (fail! ::validation-error
                     "schema validator failed"
                     context {})))]
      (if valid?
        value
        (let [explanation
              (try
                ((:explainer compiled-schema) value)
                (catch Throwable _
                  (fail! ::validation-error
                         "schema explainer failed"
                         context {})))
              error (first (:errors explanation))
              declared-keys (:declared-keys compiled-schema)
              sanitize-path
              (fn [segments marker]
                (mapv (fn [segment]
                        (if (or (and (integer? segment) (<= 0 segment))
                                (contains? declared-keys segment))
                          segment
                          marker))
                      segments))]
          (fail! ::invalid-value "value does not conform to schema" context
                 {:schema-path (sanitize-path (or (:path error) [])
                                              ::dynamic-schema-key)
                  :value-path (sanitize-path (or (:in error) [])
                                             ::dynamic-key)}))))))

(defn validate-form!
  "Compiles form and validates value. Intended for one-time plan assembly."
  [context form value]
  (validate! (compile! context form) value context))
