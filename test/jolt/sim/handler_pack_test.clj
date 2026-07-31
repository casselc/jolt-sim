(ns jolt.sim.handler-pack-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.runtime :as runtime]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---- key constructors ---------------------------------------------------

(deftest native-operation-key-builds-canonical-keys
  (is (= [:native-operation :alloc] (hp/native-operation-key :alloc)))
  (is (= [:native-operation :borrow-byte-array]
         (hp/native-operation-key :borrow-byte-array))))

(deftest native-operation-key-rejects-unknown-operations
  (let [data (ex-data-of #(hp/native-operation-key :not-an-operation))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= [:native-operation :not-an-operation] (:handler-key data)))))

(deftest foreign-function-key-defaults-capture-false
  (is (= [:foreign-function "f" [:int] :int true false]
         (hp/foreign-function-key "f" [:int] :int true))))

(deftest foreign-function-key-has-explicit-capture-arity
  (is (= [:foreign-function "f" [:int] :int true true]
         (hp/foreign-function-key "f" [:int] :int true true)))
  (is (= [:foreign-function "f" [:int :pointer] :string false false]
         (hp/foreign-function-key "f" [:int :pointer] :string false false))))

(deftest foreign-function-key-validates-its-terms
  (let [bad-symbol (ex-data-of #(hp/foreign-function-key 9 [:int] :int false))
        bad-args (ex-data-of #(hp/foreign-function-key "f" :int :int false))
        bad-return (ex-data-of #(hp/foreign-function-key "f" [:int] 9 false))
        bad-blocking (ex-data-of #(hp/foreign-function-key "f" [:int] :int 9))
        bad-capture (ex-data-of #(hp/foreign-function-key "f" [:int] :int false 9))]
    (doseq [data [bad-symbol bad-args bad-return bad-blocking bad-capture]]
      (is (= :jolt.sim.runtime/invalid-config (:type data))))))

;; ---- recursive struct argument-type keys --------------------------------

(def ^:private flat-struct-type
  [:by-value [:struct [[:x :int] [:y :pointer]]]])

(def ^:private nested-struct-type
  [:by-value [:struct [[:tag :int]
                      [:point [:struct [[:x :int] [:y :int]]]]]]])

(deftest foreign-function-key-builds-recursive-struct-keys
  ;; A struct argument type flows through the public key constructor and is
  ;; validated recursively by the runtime; the full recursive shape is
  ;; preserved verbatim in the canonical key.
  (is (= [:foreign-function "make_point" [flat-struct-type] :pointer true false]
         (hp/foreign-function-key "make_point" [flat-struct-type] :pointer true)))
  (is (= [:foreign-function "make_point" [nested-struct-type] :pointer true true]
         (hp/foreign-function-key "make_point" [nested-struct-type]
                                  :pointer true true)))
  ;; Primitive and struct types coexist within one key.
  (is (= [:foreign-function "f" [:int flat-struct-type] :int false false]
         (hp/foreign-function-key "f" [:int flat-struct-type] :int false))))

(deftest foreign-function-key-rejects-malformed-struct-shapes
  (doseq [bad-type [[:by-value [:struct []]]
                    [:struct [[:x :int]]]
                    [:by-value [:struct [[:ns/x :int]]]]
                    [:ns/by-value [:struct [[:x :int]]]]
                    [:by-value [:ns/struct [[:x :int]]]]
                    [:by-value [:struct [[:x [:by-value [:struct [[:a :int]]]]]]]]]]
    (let [data (ex-data-of
                #(hp/foreign-function-key "f" [bad-type] :int false))]
      (is (= :jolt.sim.runtime/invalid-config (:type data)) (pr-str bad-type))
      (is (= [:foreign-function "f" [bad-type] :int false false]
             (:handler-key data))
          (pr-str bad-type)))))

(deftest pack-canonicalizes-struct-handler-keys-and-composes-them
  (let [struct-key (hp/foreign-function-key
                    "make_point" [flat-struct-type] :pointer true)
        p (hp/pack :acme/structs
                   {struct-key (fn [_] 0)
                    (hp/native-operation-key :alloc) (fn [_] 1)})]
    (is (contains? (:handlers p)
                   [:foreign-function "make_point" [flat-struct-type]
                    :pointer true false]))
    (let [composed (hp/compose p)]
      (is (= #{[:foreign-function "make_point" [flat-struct-type]
                :pointer true false]
               [:native-operation :alloc]}
             (set (keys composed)))))))

;; ---- pack ---------------------------------------------------------------

(deftest pack-requires-a-namespaced-keyword-id
  (is (= :jolt.sim.handler-pack/invalid-pack-id
         (:type (ex-data-of #(hp/pack :no-namespace {})))))
  (is (= :jolt.sim.handler-pack/invalid-pack-id
         (:type (ex-data-of #(hp/pack "not-a-keyword" {})))))
  (is (= :jolt.sim.handler-pack/invalid-pack-id
         (:type (ex-data-of #(hp/pack nil {})))))
  (is (= :acme/alloc
         (:id (hp/pack :acme/alloc
                       {(hp/native-operation-key :alloc) (fn [_] 0)})))))

(deftest pack-requires-a-handler-map
  (is (= :jolt.sim.runtime/invalid-config
         (:type (ex-data-of #(hp/pack :acme/x :not-a-map))))))

(deftest pack-canonicalizes-legacy-five-element-foreign-keys
  (let [legacy [:foreign-function "f" [:int] :int true]
        p (hp/pack :acme/legacy {legacy (fn [_] 1)})]
    (is (= :acme/legacy (:id p)))
    (is (= {(conj legacy false) (get-in p [:handlers (conj legacy false)])}
           (:handlers p)))
    (is (contains? (:handlers p) [:foreign-function "f" [:int] :int true false]))))

(deftest pack-accepts-nil-and-function-values-only
  (let [with-nil (hp/pack :acme/nil
                          {(hp/native-operation-key :alloc) nil})]
    (is (contains? (:handlers with-nil) [:native-operation :alloc])))
  (is (= :jolt.sim.runtime/invalid-config
         (:type (ex-data-of
                 #(hp/pack :acme/bad
                           {(hp/native-operation-key :alloc) :not-a-fn}))))))

(deftest pack-rejects-malformed-keys
  (is (= :jolt.sim.runtime/invalid-config
         (:type (ex-data-of
                 #(hp/pack :acme/bad {:not-a-vector (fn [_])})))))
  (is (= :jolt.sim.runtime/invalid-config
         (:type (ex-data-of
                 #(hp/pack :acme/bad
                           {[:foreign-function "f" [:int] :int] (fn [_])})))))
  (is (= :jolt.sim.runtime/invalid-config
         (:type (ex-data-of
                 #(hp/pack :acme/bad
                           {[:native-operation :not-an-operation] (fn [_])}))))))

(deftest pack-rejects-two-keys-that-canonicalize-together
  ;; The legacy five-element key and its six-element capture?-false equivalent
  ;; are the same identity; supplying both in one pack is rejected rather than
  ;; silently overwriting one handler with the other.
  (let [legacy [:foreign-function "f" [:int] :int true]
        canonical (conj legacy false)
        data (ex-data-of
              #(hp/pack :acme/ambiguous
                        {legacy (fn [_] :a)
                         canonical (fn [_] :b)}))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= [canonical] (:ambiguous-keys data)))))

;; ---- compose ------------------------------------------------------------

(deftest compose-empty-returns-empty-map
  (is (= {} (hp/compose))))

(deftest compose-single-pack-returns-canonical-handlers
  (let [key (hp/foreign-function-key "f" [:int] :int true)
        p (hp/pack :acme/one {key (fn [_] 7)})]
    (is (= {key (get-in p [:handlers key])} (hp/compose p)))))

(deftest compose-merges-disjoint-packs
  (let [a (hp/pack :acme/alloc
                   {(hp/native-operation-key :alloc) (fn [_] 0)})
        legacy [:foreign-function "f" [:int] :int true false]
        b (hp/pack :acme/foreign
                   {[:foreign-function "f" [:int] :int true]
                    (fn [_] 1)})
        composed (hp/compose a b)]
    (is (= #{[:native-operation :alloc]
             [:foreign-function "f" [:int] :int true false]}
           (set (keys composed))))))

(deftest compose-rejects-duplicate-pack-ids
  (let [a (hp/pack :acme/same {(hp/native-operation-key :alloc) (fn [_] 0)})
        b (hp/pack :acme/same {(hp/native-operation-key :free) (fn [_] 0)})
        data (ex-data-of #(hp/compose a b))]
    (is (= :jolt.sim.handler-pack/duplicate-pack-id (:type data)))
    (is (= :acme/same (:pack-id data)))))

(deftest compose-rejects-a-duplicate-handler-key-across-packs
  (let [key (hp/foreign-function-key "f" [:int] :int true)
        a (hp/pack :acme/one {key (fn [_] :first)})
        ;; A different pack id registers the same canonical key. Legacy and
        ;; canonical forms both exercise the cross-pack collision.
        b-legacy (hp/pack :acme/two
                          {[:foreign-function "f" [:int] :int true]
                           (fn [_] :second)})
        data (ex-data-of #(hp/compose a b-legacy))]
    (is (= :jolt.sim.handler-pack/duplicate-handler-key (:type data)))
    (is (= [:foreign-function "f" [:int] :int true false] (:handler-key data)))
    (is (= [:acme/one :acme/two] (:pack-ids data)))))

(deftest compose-rejects-a-duplicate-key-even-with-identical-values
  (let [key (hp/native-operation-key :alloc)
        same-fn (fn [_] 0)
        a (hp/pack :acme/a {key same-fn})
        b (hp/pack :acme/b {key same-fn})
        data (ex-data-of #(hp/compose a b))]
    (is (= :jolt.sim.handler-pack/duplicate-handler-key (:type data)))
    (is (= key (:handler-key data)))
    (is (= [:acme/a :acme/b] (:pack-ids data)))))

(deftest compose-rejects-a-non-pack
  (is (= :jolt.sim.handler-pack/not-a-pack
         (:type (ex-data-of #(hp/compose :not-a-pack)))))
  (is (= :jolt.sim.handler-pack/not-a-pack
         (:type (ex-data-of #(hp/compose (hp/pack :acme/x {})
                                         {:also-not :a-pack}))))))

(deftest compose-revalidates-modified-pack-data
  (let [key (hp/native-operation-key :alloc)
        original (hp/pack :acme/original {key (fn [_] 0)})
        bad-id (assoc original :id nil)
        bad-handlers (assoc original :handlers {[:native-operation :bogus]
                                                (fn [_] 0)})]
    (is (= :jolt.sim.handler-pack/invalid-pack-id
           (:type (ex-data-of #(hp/compose bad-id)))))
    (is (= :jolt.sim.runtime/invalid-config
           (:type (ex-data-of #(hp/compose bad-handlers)))))))

;; ---- runtime compatibility ---------------------------------------------

(deftest composed-output-is-accepted-unchanged-by-runtime-config
  ;; A composed map uses the runtime's own canonical six-element foreign keys
  ;; and two-element native keys, so the runtime's public normalizer
  ;; accepts it without re-canonicalization or re-classification.
  (let [native-key (hp/native-operation-key :alloc)
        ff-key (hp/foreign-function-key "sqlite3_open" [:pointer :pointer]
                                        :int true)
        legacy [:foreign-function "sqlite3_close_v2" [:pointer] :int true]
        a (hp/pack :acme/native {native-key (fn [_] 0)})
        b (hp/pack :acme/sqlite {ff-key (fn [_] 0)
                                 legacy (fn [_] 0)})
        composed (hp/compose a b)
        revalidated (runtime/normalize-ffi-handlers composed)]
    (is (= composed revalidated))
    ;; The legacy five-element close key was canonicalized by pack to the
    ;; six-element capture?-false identity, and that identity survives the
    ;; runtime's own config check.
    (is (contains? revalidated
                   [:foreign-function "sqlite3_close_v2" [:pointer] :int
                    true false]))))
