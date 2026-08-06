(ns jolt.sim.handler-pack-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.runtime :as runtime]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---- key constructors ---------------------------------------------------

(deftest native-operation-key-builds-canonical-keys
  (is (= [:native-operation :alloc] (hp/native-operation-key :alloc)))
  (is (= [:native-operation :null?]
         (hp/native-operation-key :null?))))

(deftest native-operation-key-rejects-unknown-operations
  (let [data (ex-data-of #(hp/native-operation-key :not-an-operation))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= [:native-operation :not-an-operation] (:handler-key data)))))

(deftest foreign-function-key-defaults-capture-false-and-varargs-nil
  (is (= [:foreign-function "f" [:int] :int true false nil]
         (hp/foreign-function-key "f" [:int] :int true))))

(deftest foreign-function-key-has-explicit-capture-and-varargs-arities
  (is (= [:foreign-function "f" [:int] :int true true nil]
         (hp/foreign-function-key "f" [:int] :int true true)))
  (is (= [:foreign-function "f" [:int :pointer] :string false false nil]
         (hp/foreign-function-key "f" [:int :pointer] :string false false)))
  ;; The sixth positional argument declares the variadic boundary.
  (is (= [:foreign-function "fcntl" [:int :int :int] :int false true 2]
         (hp/foreign-function-key "fcntl" [:int :int :int] :int false true 2)))
  ;; A nil boundary through the explicit arity is identical to the default.
  (is (= [:foreign-function "f" [:int] :int true false nil]
         (hp/foreign-function-key "f" [:int] :int true false nil))))

(deftest foreign-function-key-validates-its-terms
  (let [bad-symbol (ex-data-of #(hp/foreign-function-key 9 [:int] :int false))
        bad-args (ex-data-of #(hp/foreign-function-key "f" :int :int false))
        bad-return (ex-data-of #(hp/foreign-function-key "f" [:int] 9 false))
        bad-blocking (ex-data-of #(hp/foreign-function-key "f" [:int] :int 9))
        bad-capture (ex-data-of #(hp/foreign-function-key "f" [:int] :int false 9))
        bad-varargs-zero (ex-data-of #(hp/foreign-function-key "f" [:int] :int false false 0))
        bad-varargs-range (ex-data-of #(hp/foreign-function-key "f" [:int] :int false false 2))
        bad-varargs-type (ex-data-of #(hp/foreign-function-key "f" [:int] :int false false :one))]
    (doseq [data [bad-symbol bad-args bad-return bad-blocking bad-capture
                  bad-varargs-zero bad-varargs-range bad-varargs-type]]
      (is (= :jolt.sim.runtime/invalid-config (:type data))))))

;; ---- scalar-only foreign argument-type keys -----------------------------
;;
;; The current contract accepts primitive keyword argument types only;
;; recursive by-value aggregate argument types are rejected. Variadic calls
;; are identified by an exact varargs-after boundary, not aggregate types.

(deftest foreign-function-key-rejects-aggregate-argument-types
  (doseq [bad-type [[:by-value [:struct [[:x :int] [:y :pointer]]]]
                    [:struct [[:x :int]]]
                    [:by-value [:struct []]]
                    [:by-value [:struct [[:ns/x :int]]]]]]
    (let [data (ex-data-of
                #(hp/foreign-function-key "f" [bad-type] :int false))]
      (is (= :jolt.sim.runtime/invalid-config (:type data)) (pr-str bad-type))
      (is (= [:foreign-function "f" [bad-type] :int false false nil]
             (:handler-key data))
          (pr-str bad-type))))

  ;; Scalar keyword argument types coexist within one key.
  (is (= [:foreign-function "f" [:int :pointer] :int false false nil]
         (hp/foreign-function-key "f" [:int :pointer] :int false))))

(deftest foreign-function-key-varargs-boundary-distinguishes-fixed-from-variadic
  ;; Two otherwise-identical signatures differing only in the boundary
  ;; produce distinct canonical keys, so a handler pack can register both a
  ;; fixed-arity and a variadic handler for the same symbol without collision.
  (let [fixed (hp/foreign-function-key "open" [:int :int] :int false false nil)
        variadic (hp/foreign-function-key "open" [:int :int] :int false false 1)]
    (is (= [:foreign-function "open" [:int :int] :int false false nil] fixed))
    (is (= [:foreign-function "open" [:int :int] :int false false 1] variadic))
    (is (not= fixed variadic))
    (let [p (hp/pack :acme/dual {fixed (fn [_] :fixed)
                                 variadic (fn [_] :variadic)})
          composed (hp/compose p)]
      (is (= #{fixed variadic} (set (keys composed)))))))

(deftest pack-canonicalizes-scalar-handler-keys-and-composes-them
  (let [scalar-key (hp/foreign-function-key
                    "make_point" [:int :pointer] :pointer true)
        p (hp/pack :acme/scalars
                   {scalar-key (fn [_] 0)
                    (hp/native-operation-key :alloc) (fn [_] 1)})]
    (is (contains? (:handlers p)
                   [:foreign-function "make_point" [:int :pointer]
                    :pointer true false nil]))
    (let [composed (hp/compose p)]
      (is (= #{[:foreign-function "make_point" [:int :pointer]
                :pointer true false nil]
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
    (is (= {(conj (conj legacy false) nil)
            (get-in p [:handlers (conj (conj legacy false) nil)])}
           (:handlers p)))
    (is (contains? (:handlers p)
                   [:foreign-function "f" [:int] :int true false nil]))))

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
  ;; canonicalize to the same seven-element identity; supplying both in one
  ;; pack is rejected rather than silently overwriting one handler with the
  ;; other.
  (let [legacy [:foreign-function "f" [:int] :int true]
        canonical [:foreign-function "f" [:int] :int true false nil]
        data (ex-data-of
              #(hp/pack :acme/ambiguous
                        {legacy (fn [_] :a)
                         (conj legacy false) (fn [_] :b)}))]
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
             [:foreign-function "f" [:int] :int true false nil]}
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
    (is (= [:foreign-function "f" [:int] :int true false nil] (:handler-key data)))
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
  ;; A composed map uses the runtime's own canonical seven-element foreign keys
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
    ;; seven-element capture?-false/varargs-nil identity, and that identity
    ;; survives the runtime's own config check.
    (is (contains? revalidated
                   [:foreign-function "sqlite3_close_v2" [:pointer] :int
                    true false nil]))))
