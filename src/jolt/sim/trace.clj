(ns jolt.sim.trace
  "Canonical, readable EDN projections and deterministic simulation events.

  Caller-controlled state may contain nil, booleans, integers, ratios,
  floating-point numbers, strings, characters, keywords, symbols, byte arrays,
  and finite acyclic combinations of vectors, lists, sequences, sets, and
  plain maps. Metadata, records, functions, and arbitrary host objects are
  rejected. Byte arrays are projected explicitly as unsigned octets, but are
  rejected recursively in map-key and set-member positions because mutable
  arrays have identity equality and cannot be restored as stable keys.

  `canonical-value` tags every accepted value kind. The resulting EDN is a
  collision-free logical representation within this domain and never relies
  on a runtime hash or host-object printer."
  (:require [clojure.edn :as edn]))

(def unsupported-value :jolt.sim.trace/unsupported-value)
(def malformed-canonical-value :jolt.sim.trace/malformed-canonical-value)

(def ^:private canonical-tags
  #{:jolt.sim.value/nil
    :jolt.sim.value/boolean
    :jolt.sim.value/integer
    :jolt.sim.value/ratio
    :jolt.sim.value/float
    :jolt.sim.value/string
    :jolt.sim.value/character
    :jolt.sim.value/keyword
    :jolt.sim.value/symbol
    :jolt.sim.value/bytes
    :jolt.sim.value/vector
    :jolt.sim.value/list
    :jolt.sim.value/sequence
    :jolt.sim.value/set
    :jolt.sim.value/map})

(defn- unsupported! [path reason value]
  (throw
   (ex-info
    "Value is outside jolt-sim's stable trace domain"
    {:type unsupported-value
     :path path
     :reason reason
     :value-class (str (class value))})))

(defn- ensure-no-metadata! [value path]
  (when (some? (meta value))
    (unsupported! path :metadata value)))

(defn- canonical-order-key [value]
  (pr-str value))

(declare canonical-value)

(defn- contains-bytes? [value]
  (cond
    (bytes? value)
    true

    (map? value)
    (boolean
     (some (fn [[key entry-value]]
             (or (contains-bytes? key)
                 (contains-bytes? entry-value)))
           value))

    (or (set? value) (sequential? value))
    (boolean (some contains-bytes? value))

    :else
    false))

(defn- canonical-sequence [tag values path]
  [tag
   (mapv (fn [index value]
           (canonical-value value (conj path index)))
         (range)
         values)])

(defn- canonical-set [values path]
  (let [projected
        (mapv (fn [value]
                (when (contains-bytes? value)
                  (unsupported! path :mutable-set-member value))
                (canonical-value value (conj path :set-member)))
              values)]
    (when-not (= (count projected) (count (set projected)))
      (unsupported! path :canonical-member-collision values))
    [:jolt.sim.value/set
     (vec (sort-by canonical-order-key projected))]))

(defn- canonical-map [value path]
  (when (record? value)
    (unsupported! path :record value))
  (let [entries
        (reduce-kv
         (fn [result key entry-value]
           (when (contains-bytes? key)
             (unsupported! path :mutable-map-key key))
           (conj result
                 [(canonical-value key (conj path :map-key))
                  (canonical-value entry-value
                                   (conj path :map-value))]))
         []
         value)]
    (when-not (= (count entries)
                 (count (set (map first entries))))
      (unsupported! path :canonical-key-collision value))
    [:jolt.sim.value/map
     (vec (sort-by (comp canonical-order-key first) entries))]))

(defn canonical-value
  "Returns a collision-free, readable EDN projection of an accepted value.

  The optional path arity exists so callers receive a useful location when a
  nested value is unsupported."
  ([value]
   (canonical-value value []))
  ([value path]
   (ensure-no-metadata! value path)
   (cond
     (nil? value)
     [:jolt.sim.value/nil]

     (boolean? value)
     [:jolt.sim.value/boolean value]

     (integer? value)
     [:jolt.sim.value/integer (str value)]

     (ratio? value)
     [:jolt.sim.value/ratio
      (str (numerator value))
      (str (denominator value))]

     (float? value)
     [:jolt.sim.value/float (pr-str value)]

     (string? value)
     [:jolt.sim.value/string value]

     (char? value)
     [:jolt.sim.value/character (str value)]

     (keyword? value)
     [:jolt.sim.value/keyword (namespace value) (name value)]

     (symbol? value)
     [:jolt.sim.value/symbol (namespace value) (name value)]

     (bytes? value)
     [:jolt.sim.value/bytes
      (mapv #(bit-and (long %) 0xff) (seq value))]

     (vector? value)
     (canonical-sequence :jolt.sim.value/vector value path)

     (list? value)
     (canonical-sequence :jolt.sim.value/list value path)

     (set? value)
     (canonical-set value path)

     (map? value)
     (canonical-map value path)

     (sequential? value)
     (canonical-sequence :jolt.sim.value/sequence value path)

     :else
     (unsupported! path :unsupported-type value))))

(declare canonical-form?)

(defn- canonical-children? [values]
  (and (vector? values)
       (every? canonical-form? values)))

(defn- canonical-contains-bytes? [value]
  (and
   (vector? value)
   (case (first value)
     :jolt.sim.value/bytes
     true

     (:jolt.sim.value/vector
      :jolt.sim.value/list
      :jolt.sim.value/sequence
      :jolt.sim.value/set)
     (boolean (some canonical-contains-bytes? (nth value 1 [])))

     :jolt.sim.value/map
     (boolean
      (some (fn [entry]
              (or (canonical-contains-bytes? (nth entry 0 nil))
                  (canonical-contains-bytes? (nth entry 1 nil))))
            (nth value 1 [])))

     false)))

(defn- sorted-canonical? [values key-fn]
  (= values (vec (sort-by key-fn values))))

(defn- canonical-map-form? [entries]
  (and
   (vector? entries)
   (every? (fn [entry]
             (and (vector? entry)
                  (= 2 (count entry))
                  (canonical-form? (nth entry 0))
                  (canonical-form? (nth entry 1))))
           entries)
   (sorted-canonical? entries (comp canonical-order-key first))
   (= (count entries)
      (count (set (map (comp pr-str first) entries))))))

(defn- canonical-integer-string? [value]
  (try
    (let [parsed (edn/read-string value)]
      (and (integer? parsed)
           (= value (str parsed))))
    (catch :default _
      false)))

(defn- canonical-ratio-strings? [numerator-text denominator-text]
  (try
    (let [parsed (edn/read-string
                  (str numerator-text "/" denominator-text))]
      (and (ratio? parsed)
           (= numerator-text (str (numerator parsed)))
           (= denominator-text (str (denominator parsed)))))
    (catch :default _
      false)))

(defn- canonical-float-string? [value]
  (try
    (let [parsed (edn/read-string value)]
      (and (float? parsed)
           (= value (pr-str parsed))))
    (catch :default _
      false)))

(defn- canonical-named-form? [tag namespace-text name-text]
  (try
    (let [value
          (case tag
            :jolt.sim.value/keyword
            (if namespace-text
              (keyword namespace-text name-text)
              (keyword name-text))

            :jolt.sim.value/symbol
            (if namespace-text
              (symbol namespace-text name-text)
              (symbol name-text)))]
      (and (= namespace-text (namespace value))
           (= name-text (name value))))
    (catch :default _
      false)))

(defn canonical-form?
  "True when `value` has the exact shape emitted by `canonical-value`."
  [value]
  (and
   (vector? value)
   (contains? canonical-tags (first value))
   (case (first value)
     :jolt.sim.value/nil
     (= 1 (count value))

     :jolt.sim.value/boolean
     (and (= 2 (count value)) (boolean? (nth value 1)))

     :jolt.sim.value/integer
     (and (= 2 (count value))
          (string? (nth value 1))
          (canonical-integer-string? (nth value 1)))

     :jolt.sim.value/ratio
     (and (= 3 (count value))
          (string? (nth value 1))
          (string? (nth value 2))
          (canonical-ratio-strings? (nth value 1) (nth value 2)))

     :jolt.sim.value/float
     (and (= 2 (count value))
          (string? (nth value 1))
          (canonical-float-string? (nth value 1)))

     :jolt.sim.value/string
     (and (= 2 (count value)) (string? (nth value 1)))

     :jolt.sim.value/character
     (and (= 2 (count value))
          (string? (nth value 1))
          (= 1 (count (nth value 1))))

     (:jolt.sim.value/keyword :jolt.sim.value/symbol)
     (and (= 3 (count value))
          (or (nil? (nth value 1)) (string? (nth value 1)))
          (string? (nth value 2))
          (canonical-named-form?
           (nth value 0) (nth value 1) (nth value 2)))

     :jolt.sim.value/bytes
     (and (= 2 (count value))
          (vector? (nth value 1))
          (every? #(and (integer? %) (<= 0 % 255))
                  (nth value 1)))

     (:jolt.sim.value/vector
      :jolt.sim.value/list
      :jolt.sim.value/sequence)
     (and (= 2 (count value))
          (canonical-children? (nth value 1)))

     :jolt.sim.value/set
     (and (= 2 (count value))
          (canonical-children? (nth value 1))
          (not-any? canonical-contains-bytes? (nth value 1))
          (sorted-canonical? (nth value 1) canonical-order-key)
          (= (count (nth value 1))
             (count (set (map pr-str (nth value 1))))))

     :jolt.sim.value/map
     (and (= 2 (count value))
          (canonical-map-form? (nth value 1))
          (not-any? (fn [entry]
                      (canonical-contains-bytes? (nth entry 0)))
                    (nth value 1)))

     false)))

(defn restore-value
  "Restores a fresh ordinary value from a `canonical-value` projection.

  Byte arrays are newly allocated on every call. Persistent collection
  containers are also rebuilt, so callers can safely receive a restored value
  without gaining access to mutable storage retained by a simulator."
  [value]
  (when-not (canonical-form? value)
    (throw
     (ex-info
      "Malformed jolt-sim canonical value"
      {:type malformed-canonical-value
       :value value})))
  (let [tag (first value)]
    (case tag
      :jolt.sim.value/nil
      nil

      :jolt.sim.value/boolean
      (nth value 1)

      :jolt.sim.value/integer
      (edn/read-string (nth value 1))

      :jolt.sim.value/ratio
      (edn/read-string (str (nth value 1) "/" (nth value 2)))

      :jolt.sim.value/float
      (edn/read-string (nth value 1))

      :jolt.sim.value/string
      (nth value 1)

      :jolt.sim.value/character
      (nth (nth value 1) 0)

      :jolt.sim.value/keyword
      (let [namespace-text (nth value 1)
            name-text (nth value 2)]
        (if namespace-text
          (keyword namespace-text name-text)
          (keyword name-text)))

      :jolt.sim.value/symbol
      (let [namespace-text (nth value 1)
            name-text (nth value 2)]
        (if namespace-text
          (symbol namespace-text name-text)
          (symbol name-text)))

      :jolt.sim.value/bytes
      (byte-array (nth value 1))

      :jolt.sim.value/vector
      (mapv restore-value (nth value 1))

      :jolt.sim.value/list
      (apply list (map restore-value (nth value 1)))

      :jolt.sim.value/sequence
      (map identity (mapv restore-value (nth value 1)))

      :jolt.sim.value/set
      (set (map restore-value (nth value 1)))

      :jolt.sim.value/map
      (reduce
       (fn [result entry]
         (assoc result
                (restore-value (nth entry 0))
                (restore-value (nth entry 1))))
       {}
       (nth value 1)))))

(defn normalize-error
  "Recursively replaces raw exception values with ordinary immutable data.

  This function is applied at every failed-task boundary before an error is
  retained in task state or projected into a trace."
  [value]
  (cond
    (instance? Throwable value)
    {:kind :jolt.sim/exception
     :class (str (class value))
     :message (or (ex-message value) (str value))
     :data (normalize-error (ex-data value))}

    (bytes? value)
    value

    (vector? value)
    (mapv normalize-error value)

    (list? value)
    (apply list (map normalize-error value))

    (set? value)
    (set (map normalize-error value))

    (map? value)
    (reduce-kv (fn [result key entry-value]
                 (assoc result
                        (normalize-error key)
                        (normalize-error entry-value)))
               {}
               value)

    (sequential? value)
    (mapv normalize-error value)

    :else
    value))

;; Events use fixed-position vectors. Caller-controlled values embedded in an
;; event are canonical projections, so a generated trace is directly readable
;; and replayable EDN.

(defn initial-event [projection]
  [:run/initial projection])

(defn choose-event [step time enabled chosen]
  [:schedule/choose step time enabled chosen])

(defn transition-event
  [step time task op site wake wake-at projection]
  [:task/transition step time task op site wake wake-at projection])

(defn time-event [step from to awakened projection]
  [:time/advance step from to awakened projection])

(defn completed-event [step time projection]
  [:run/completed step time projection])

(defn failed-event [step time task error projection]
  [:run/failed step time task error projection])

(defn deadlock-event [step time blocked projection]
  [:run/deadlock step time blocked projection])

(defn step-limit-event [step time projection]
  [:run/step-limit step time projection])

(defn choice-event? [event]
  (and (vector? event)
       (= 5 (count event))
       (= :schedule/choose (nth event 0))))

(defn choice-events [events]
  (vec (filter choice-event? events)))

(defn choice-enabled [event]
  (nth event 3))

(defn choice-task [event]
  (nth event 4))

(defn- canonical-compare [left right]
  (compare (pr-str (canonical-value left))
           (pr-str (canonical-value right))))

(declare ordered-edn-value)

(defn- ordered-map [value]
  (reduce-kv (fn [result key entry-value]
               (assoc result
                      (ordered-edn-value key)
                      (ordered-edn-value entry-value)))
             (sorted-map-by canonical-compare)
             value))

(defn- ordered-edn-value [value]
  (cond
    (bytes? value)
    (canonical-value value)

    (map? value)
    (ordered-map value)

    (set? value)
    (into (sorted-set-by canonical-compare)
          (map ordered-edn-value)
          value)

    (vector? value)
    (mapv ordered-edn-value value)

    (list? value)
    (apply list (map ordered-edn-value value))

    (sequential? value)
    (mapv ordered-edn-value value)

    :else value))

(defn canonical-edn
  "Returns byte-stable, directly readable EDN for a generated event sequence."
  [events]
  ;; Validate the complete tree before printing. Generated trace projections
  ;; already encode byte arrays and caller collection types collision-free.
  (canonical-value events)
  (pr-str (ordered-edn-value events)))
