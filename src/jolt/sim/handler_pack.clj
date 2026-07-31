(ns jolt.sim.handler-pack
  "Public helpers for composing named FFI handler packs.

  Packs remain plain data and compose returns the canonical :ffi-handlers map
  consumed by jolt.sim.runtime/run-controlled. Runtime owns validation and
  canonicalization; this namespace adds stable key constructors, pack identity,
  and fail-closed collision attribution without a global registry."
  (:require [jolt.sim.runtime :as runtime]))

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- canonical-key [key]
  (first (keys (runtime/normalize-ffi-handlers {key nil}))))

(defn native-operation-key
  "Builds and validates one [:native-operation operation] handler key against
  the runtime's current controller contract."
  [operation]
  (canonical-key [:native-operation operation]))

(defn foreign-function-key
  "Builds and validates one canonical foreign-function handler key. capture?
  defaults to false. argument-types is a vector whose entries are primitive
  keywords or recursive [:by-value [:struct [[field field-type] ...]]] struct
  shapes; the runtime validates the full recursive identity."
  ([symbol argument-types return-type blocking?]
   (foreign-function-key symbol argument-types return-type blocking? false))
  ([symbol argument-types return-type blocking? capture?]
   (canonical-key
    [:foreign-function symbol argument-types return-type blocking? capture?])))

(defn pack
  "Creates a named handler pack. id must be a namespaced keyword. handlers is
  validated and canonicalized immediately through the runtime's exact public
  normalizer; nil remains a valid handled result."
  [id handlers]
  (when-not (and (keyword? id) (namespace id))
    (fail! :jolt.sim.handler-pack/invalid-pack-id
           "handler pack id must be a namespaced keyword"
           {:pack-id id}))
  {:jolt.sim.handler-pack/type ::pack
   :id id
   :handlers (runtime/normalize-ffi-handlers handlers)})

(defn- ensure-pack [value]
  (when-not (and (map? value)
                 (= ::pack (:jolt.sim.handler-pack/type value)))
    (fail! :jolt.sim.handler-pack/not-a-pack
           "compose accepts only values returned by pack"
           {:value value}))
  ;; Packs are ordinary data by design. Reconstruct through pack so assoc or
  ;; deserialization cannot smuggle an invalid id, key, or handler past the
  ;; collision check.
  (pack (:id value) (:handlers value)))

(defn compose
  "Returns one canonical :ffi-handlers map from zero or more named packs.

  Duplicate pack ids and duplicate canonical handler keys always fail. The
  duplicate-key error identifies the key and both owners in input order; even
  identical handler values do not make ambiguous ownership acceptable."
  [& packs]
  (loop [remaining packs
         seen-ids #{}
         key-owners {}
         result {}]
    (if-let [ps (seq remaining)]
      (let [p (ensure-pack (first ps))
            id (:id p)]
        (when (contains? seen-ids id)
          (fail! :jolt.sim.handler-pack/duplicate-pack-id
                 "two handler packs share the same id"
                 {:pack-id id}))
        (let [[next-result next-owners]
              (reduce
               (fn [[handlers owners] [key value]]
                 (when (contains? owners key)
                   (fail! :jolt.sim.handler-pack/duplicate-handler-key
                          "a handler key is owned by more than one pack"
                          {:handler-key key
                           :pack-ids [(get owners key) id]}))
                 [(assoc handlers key value) (assoc owners key id)])
               [result key-owners]
               (:handlers p))]
          (recur (next ps)
                 (conj seen-ids id)
                 next-owners
                 next-result)))
      result)))
