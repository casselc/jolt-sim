(ns jolt.sim.ffi-memory
  "Deterministic simulated native memory exposed as jolt.sim.runtime
  :ffi-handlers for the 13 ABI v2 native operations.

  A world owns concurrency-safe immutable byte-vector state behind a single
  atom: every mutation is a compare-and-set! that atomically replaces the
  affected immutable allocation record. Incoming byte arrays are copied to
  immutable vectors before mutation; byte-array results are always fresh.

  Allocation bases are deterministic: aligned to :alignment, monotonically
  increasing, with a :alignment-byte guard gap between successive payloads.
  No OS access of any kind happens here.

  Default config is LP64 little endian: pointer-size 8, long-size 8,
  base-address 0x10000 (aligned), alignment 8, available-libraries :any.")

;; ---- config -------------------------------------------------------------

(def ^:private supported-config-keys
  #{:byte-order :pointer-size :long-size :base-address
    :alignment :available-libraries})

(def ^:private default-config
  {:byte-order :little
   :pointer-size 8
   :long-size 8
   :base-address 0x10000
   :alignment 8
   :available-libraries :any})

(defn- power-of-two? [n]
  (and (integer? n)
       (pos? n)
       (loop [x n]
         (cond
           (= 1 x) true
           (odd? x) false
           :else (recur (quot x 2))))))

(defn- align-up [x alignment]
  (+ x (mod (- alignment (mod x alignment)) alignment)))

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- validate-config [config]
  (when-not (map? config)
    (fail! :jolt.sim.ffi-memory/invalid-config
           "ffi-memory world config must be a map"
           {:config config}))
  (let [unknown (vec (sort (remove supported-config-keys (keys config))))]
    (when (seq unknown)
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory world config contains unsupported keys"
             {:unknown-keys unknown})))
  (let [bo (:byte-order config)]
    (when (and (contains? config :byte-order)
               (not (#{:little :big} bo)))
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory :byte-order must be :little or :big"
             {:byte-order bo})))
  (let [ps (:pointer-size config)]
    (when (and (contains? config :pointer-size)
               (not (#{4 8} ps)))
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory :pointer-size must be 4 or 8"
             {:pointer-size ps})))
  (let [ls (:long-size config)]
    (when (and (contains? config :long-size)
               (not (#{4 8} ls)))
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory :long-size must be 4 or 8"
             {:long-size ls})))
  (let [ba (:base-address config)]
    (when (and (contains? config :base-address)
               (not (and (integer? ba) (pos? ba))))
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory :base-address must be a positive integer"
             {:base-address ba})))
  (let [al (:alignment config)]
    (when (and (contains? config :alignment)
               (not (power-of-two? al)))
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory :alignment must be a positive power of two"
             {:alignment al})))
  (let [av (:available-libraries config)]
    (when (and (contains? config :available-libraries)
               (not (or (= :any av)
                        (and (set? av) (every? string? av)))))
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory :available-libraries must be :any or a set of strings"
             {:available-libraries av})))
  (let [merged (merge default-config config)
        base (:base-address merged)
        al (:alignment merged)
        aligned-base (align-up base al)
        address-limit
        (if (= 4 (:pointer-size merged))
          4294967295
          18446744073709551615)]
    (when (> aligned-base address-limit)
      (fail! :jolt.sim.ffi-memory/invalid-config
             "ffi-memory :base-address does not fit the configured pointer width"
             {:base-address base
              :aligned-base aligned-base
              :pointer-size (:pointer-size merged)}))
    (assoc merged :base-address aligned-base)))

;; ---- scalar type tables -------------------------------------------------

(def ^:private fixed-type-sizes
  {:int 4 :uint 4
   :int16 2 :short 2 :uint16 2 :ushort 2
   :int64 8 :uint64 8
   :uint8 1 :u8 1 :byte 1
   :int8 1 :i8 1
   :char 1
   :float 4 :double 8})

(def ^:private long-width-types #{:long :ulong})

(def ^:private pointer-width-types
  #{:size_t :ssize_t :iptr :uptr :pointer :void*})

(def ^:private signed-types
  #{:int :int16 :short :int8 :i8 :long :int64 :ssize_t :iptr})

(def ^:private unsupported-read-write-types #{:float :double})

(def ^:private width-moduli
  {1 256
   2 65536
   4 4294967296
   8 18446744073709551616})

(def ^:private width-sign-limits
  {1 128
   2 32768
   4 2147483648
   8 9223372036854775808})

(def ^:private byte-factors
  [1 256 65536 16777216
   4294967296 1099511627776 281474976710656 72057594037927936])

(defn- sizeof-type [config type]
  (cond
    (contains? fixed-type-sizes type) (fixed-type-sizes type)
    (contains? long-width-types type) (:long-size config)
    (contains? pointer-width-types type) (:pointer-size config)
    :else
    (fail! :jolt.sim.ffi-memory/invalid-argument
           (str "Unsupported sizeof type: " (pr-str type))
           {:type-arg type})))

(defn- signed-limit [n-bytes]
  (width-sign-limits n-bytes))

(defn- width-modulus [n-bytes]
  (width-moduli n-bytes))

(defn- encode-uint [value n-bytes byte-order]
  (let [normalized (mod value (width-modulus n-bytes))
        le (mapv (fn [i]
                   (mod (quot normalized (nth byte-factors i)) 256))
                 (range n-bytes))]
    (if (= :big byte-order) (vec (reverse le)) le)))

(defn- decode-uint [v offset n-bytes byte-order]
  (let [raw (mapv #(nth v (+ offset %)) (range n-bytes))
        le (if (= :big byte-order) (vec (reverse raw)) raw)]
    (reduce (fn [acc i]
              (+ acc (* (nth le i) (nth byte-factors i))))
            0
            (range n-bytes))))

;; ---- byte array / vector helpers ---------------------------------------

(defn- byte-array->uvec [arr]
  (let [n (alength arr)]
    (loop [i 0 out (transient [])]
      (if (== i n)
        (persistent! out)
        (recur (inc i)
               (conj! out (bit-and 0xFF (int (aget arr i)))))))))

(defn- uvec->byte-array [v]
  (let [n (count v)
        arr (byte-array n)]
    (loop [i 0]
      (when (< i n)
        (aset arr i (nth v i))
        (recur (inc i))))
    arr))

(defn- vec-assoc-range [v offset new-bytes]
  (let [m (count new-bytes)]
    (loop [i 0 v v]
      (if (== i m)
        v
        (recur (inc i) (assoc v (+ offset i) (nth new-bytes i)))))))

(defn- decode-utf8 [uvec]
  (let [ba (uvec->byte-array uvec)
        s (String. ba "UTF-8")]
    (when-not (= (byte-array->uvec (.getBytes s "UTF-8")) (vec uvec))
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "Native string bytes are not valid UTF-8"
             {:bytes (vec uvec)}))
    s))

(defn- decode-utf8-with-latin1-fallback [uvec]
  (let [ba (uvec->byte-array uvec)]
    (try
      (let [s (String. ba "UTF-8")]
        (if (= (byte-array->uvec (.getBytes s "UTF-8")) (vec uvec))
          s
          (String. ba "ISO-8859-1")))
      (catch :default _
        (String. ba "ISO-8859-1")))))

;; ---- heap helpers -------------------------------------------------------

(defn- find-allocation
  "Returns the allocation record whose [base, base+size) contains ptr,
  including retained freed-allocation evidence, else nil."
  [heap ptr]
  (loop [entries (seq heap)]
    (when-first [[_ rec] entries]
      (if (and (<= (:base rec) ptr)
               (< ptr (+ (:base rec) (:size rec))))
        rec
        (recur (next entries))))))

(defn- find-containing [heap ptr]
  (let [record (find-allocation heap ptr)]
    (when (and record (not (:freed? record)))
      record)))

(defn- pointer-miss! [heap ptr]
  (if (find-allocation heap ptr)
    (fail! :jolt.sim.ffi-memory/use-after-free
           "Pointer addresses an allocation that has been freed"
           {:pointer ptr})
    (fail! :jolt.sim.ffi-memory/unknown-pointer
           "Pointer does not address a known allocation"
           {:pointer ptr})))

(defn- resolve-live! [heap ptr length]
  (let [rec (find-containing heap ptr)]
    (when-not rec
      (pointer-miss! heap ptr))
    (let [offset (- ptr (:base rec))]
      (when (> (+ offset length) (:size rec))
        (fail! :jolt.sim.ffi-memory/out-of-bounds
               "Native access exceeds its allocation bounds"
               {:pointer ptr
                :base (:base rec)
                :size (:size rec)
                :offset offset
                :length length}))
      {:record rec :offset offset})))

(defn- resolve-relative!
  "Resolves ptr to one live allocation first, then applies signed
  relative-offset within that same allocation. This prevents an offset from
  jumping across a guard gap into a different live allocation."
  [heap ptr relative-offset length]
  (let [rec (find-containing heap ptr)]
    (when-not rec
      (pointer-miss! heap ptr))
    (let [pointer-offset (- ptr (:base rec))
          offset (+ pointer-offset relative-offset)]
      (when (or (neg? offset)
                (> (+ offset length) (:size rec)))
        (fail! :jolt.sim.ffi-memory/out-of-bounds
               "Relative native access exceeds its original allocation bounds"
               {:pointer ptr
                :relative-offset relative-offset
                :base (:base rec)
                :size (:size rec)
                :offset offset
                :length length}))
      {:record rec :offset offset})))

(defn- validate-pointer! [operation ptr]
  (when-not (and (integer? ptr) (not (neg? ptr)))
    (fail! :jolt.sim.ffi-memory/invalid-argument
           (str (name operation) " pointer must be a non-negative integer")
           {:operation operation :pointer ptr}))
  ptr)

(defn- validate-arity! [operation arguments expected]
  (when-not (if (set? expected)
              (contains? expected (count arguments))
              (= expected (count arguments)))
    (fail! :jolt.sim.ffi-memory/invalid-argument
           (str (name operation) " received the wrong number of arguments")
           {:operation operation
            :expected-arities (if (set? expected) expected #{expected})
            :actual-arity (count arguments)}))
  arguments)

(defn- validate-min-arity! [operation arguments minimum]
  (when (< (count arguments) minimum)
    (fail! :jolt.sim.ffi-memory/invalid-argument
           (str (name operation) " received too few arguments")
           {:operation operation
            :minimum-arity minimum
            :actual-arity (count arguments)}))
  arguments)

(defn- find-nul [bytes offset size]
  (loop [i offset]
    (cond
      (>= i size) nil
      (zero? (nth bytes i)) i
      :else (recur (inc i)))))

(defn- cas-update!
  "Atomically applies f to the atom's state. f returns [new-state result] and
  must be free of side effects: compare-and-set! may re-invoke it on
  contention, and only the winning call commits. A throw from f propagates
  without committing."
  [atom- f]
  (loop []
    (let [old @atom-
          [new ret] (f old)]
      (if (compare-and-set! atom- old new)
        ret
        (recur)))))

(defn- write-uvec-at!
  "Atomically writes the unsigned byte vector into the live allocation that
  contains ptr. Zero length is a no-op. Throws typed errors without
  committing on failure."
  [world ptr uvec]
  (let [length (count uvec)]
    (if (zero? length)
      nil
      (cas-update!
       (:state world)
       (fn [s]
         (let [heap (:heap s)
               rec (find-containing heap ptr)]
           (when-not rec
             (pointer-miss! heap ptr))
           (let [offset (- ptr (:base rec))]
             (when (> (+ offset length) (:size rec))
               (fail! :jolt.sim.ffi-memory/out-of-bounds
                      "Native write exceeds its allocation bounds"
                      {:pointer ptr
                       :base (:base rec)
                       :size (:size rec)
                       :offset offset
                       :length length}))
             (let [new-bytes (vec-assoc-range (:bytes rec) offset uvec)]
               [(assoc-in s [:heap (:base rec) :bytes] new-bytes)
                nil]))))))))

(defn- write-uvec-relative-at!
  "Atomically writes bytes at signed relative-offset from ptr while retaining
  ptr's original allocation as the authority for the entire range."
  [world ptr relative-offset uvec]
  (cas-update!
   (:state world)
   (fn [s]
     (let [{:keys [record offset]}
           (resolve-relative!
            (:heap s) ptr relative-offset (count uvec))
           new-bytes (vec-assoc-range (:bytes record) offset uvec)]
       [(assoc-in s [:heap (:base record) :bytes] new-bytes)
        nil]))))

(defn- allocate-uvec!
  "Atomically allocates one non-empty unsigned byte vector and returns its
  deterministic base address."
  [world bytes]
  (let [size (count bytes)
        config (:config world)
        alignment (:alignment config)
        address-limit (dec (width-modulus (:pointer-size config)))]
    (cas-update!
     (:state world)
     (fn [s]
       (let [base (:next-base s)
             last-address (+ base (dec size))]
         (when (> last-address address-limit)
           (fail! :jolt.sim.ffi-memory/invalid-argument
                  "Allocation does not fit the configured pointer width"
                  {:base base
                   :size size
                   :pointer-size (:pointer-size config)}))
         (let [rec {:base base
                    :size size
                    :bytes bytes
                    :freed? false}
               next-base (align-up (+ base size alignment) alignment)]
           [(-> s
                (assoc :next-base next-base)
                (assoc-in [:heap base] rec))
            base]))))))

;; ---- native operations --------------------------------------------------

(defn- op-load-library [world descriptor]
  (let [arguments
        (:arguments descriptor)
        name (first arguments)]
    (cond
      (nil? name)
      (cas-update! (:state world)
                   (fn [s] [(update s :loaded-libraries conj nil) nil]))

      (not (string? name))
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "load-library name must be a string or nil"
             {:name name})

      :else
      (let [avail (:available-libraries (:config world))]
        (when-not (or (= :any avail)
                      (and (set? avail) (contains? avail name)))
          (fail! :jolt.sim.ffi-memory/unavailable-library
                 "Requested library is not available in this world"
                 {:name name :available-libraries avail}))
        (cas-update! (:state world)
                     (fn [s] [(update s :loaded-libraries conj name) nil]))))))

(defn- op-loaded? [world descriptor]
  (let [[name] (validate-arity! :loaded? (:arguments descriptor) 1)]
    (when-not (string? name)
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "loaded? name must be a string"
             {:name name}))
    (contains? (:loaded-libraries @(:state world)) name)))

(defn- op-alloc [world descriptor]
  (let [[size] (validate-arity! :alloc (:arguments descriptor) 1)]
    (when-not (and (integer? size) (pos? size))
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "alloc size must be a positive integer"
             {:size size}))
    (allocate-uvec! world (vec (repeat size 0)))))

(defn- op-free [world descriptor]
  (let [[ptr] (validate-arity! :free (:arguments descriptor) 1)]
    (validate-pointer! :free ptr)
    (cond
      (zero? ptr) nil
      :else
      (cas-update!
       (:state world)
       (fn [s]
         (let [heap (:heap s)
               exact (get heap ptr)]
           (cond
             (and exact (not (:freed? exact)))
             [(assoc-in s [:heap ptr :freed?] true) nil]

             (and exact (:freed? exact))
             (fail! :jolt.sim.ffi-memory/double-free
                    "Pointer has already been freed"
                    {:pointer ptr})

             :else
             (if (find-containing heap ptr)
               (fail! :jolt.sim.ffi-memory/invalid-free
                      "free requires an allocation base, not an interior pointer"
                      {:pointer ptr})
               (fail! :jolt.sim.ffi-memory/unknown-pointer
                      "Pointer is not a live allocation base"
                      {:pointer ptr})))))))))

(defn- op-read [world descriptor]
  (let [arguments (validate-min-arity! :read (:arguments descriptor) 2)
        [ptr type] arguments
        relative-offset (if (>= (count arguments) 3) (nth arguments 2) 0)]
    (validate-pointer! :read ptr)
    (when-not (integer? relative-offset)
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "read offset must be an integer"
             {:offset relative-offset}))
    (when (contains? unsupported-read-write-types type)
      (fail! :jolt.sim.ffi-memory/unsupported-scalar-type
             "Scalar float/double reads are unsupported in this slice"
             {:type-arg type}))
    (let [config (:config world)
          n (sizeof-type config type)]
      (if (= :char type)
        (let [s @(:state world)
              {:keys [record offset]}
              (resolve-relative! (:heap s) ptr relative-offset n)]
          (char (nth (:bytes record) offset)))
        (let [s @(:state world)
              {:keys [record offset]}
              (resolve-relative! (:heap s) ptr relative-offset n)
              u (decode-uint (:bytes record) offset n (:byte-order config))]
          (if (contains? signed-types type)
            (let [limit (signed-limit n)]
              (if (>= u limit) (- u (width-modulus n)) u))
            u))))))

(defn- op-write [world descriptor]
  (let [[ptr type relative-offset value]
        (validate-arity! :write (:arguments descriptor) 4)]
    (validate-pointer! :write ptr)
    (when-not (integer? relative-offset)
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "write offset must be an integer"
             {:offset relative-offset}))
    (when (contains? unsupported-read-write-types type)
      (fail! :jolt.sim.ffi-memory/unsupported-scalar-type
             "Scalar float/double writes are unsupported in this slice"
             {:type-arg type}))
    (let [config (:config world)
          n (sizeof-type config type)]
      (if (= :char type)
        (do (when-not (and (char? value) (<= (int value) 255))
              (fail! :jolt.sim.ffi-memory/invalid-argument
                     ":char write requires a character in the C char range"
                     {:value value}))
            (write-uvec-relative-at!
             world ptr relative-offset [(int value)])
            nil)
        (do (when-not (integer? value)
              (fail! :jolt.sim.ffi-memory/invalid-argument
                     "Scalar write value must be an integer"
                     {:value value}))
            (let [limit (signed-limit n)
                  maxu (dec (width-modulus n))]
              (when (or (< value (- limit)) (> value maxu))
                (fail! :jolt.sim.ffi-memory/invalid-argument
                       "Scalar write value is out of range for its width"
                       {:value value :width-bytes n
                        :min (- limit) :max maxu}))
              (write-uvec-relative-at!
               world
               ptr
               relative-offset
               (encode-uint value n (:byte-order config)))
              nil))))))

(defn- op-sizeof [world descriptor]
  (let [[type] (validate-arity! :sizeof (:arguments descriptor) 1)]
    (sizeof-type (:config world) type)))

(defn- op-read-bytes [world descriptor]
  (let [[ptr len] (validate-arity! :read-bytes (:arguments descriptor) 2)]
    (validate-pointer! :read-bytes ptr)
    (when-not (and (integer? len) (not (neg? len)))
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "read-bytes length must be a non-negative integer"
             {:length len}))
    (if (zero? len)
      ""
      (let [s @(:state world)
            {:keys [record offset]} (resolve-live! (:heap s) ptr len)
            uvec (subvec (:bytes record) offset (+ offset len))]
        (decode-utf8-with-latin1-fallback uvec)))))

(defn- op-write-bytes [world descriptor]
  (let [[ptr s] (validate-arity! :write-bytes (:arguments descriptor) 2)]
    (validate-pointer! :write-bytes ptr)
    (when-not (string? s)
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "write-bytes requires a string"
             {:value s}))
    (let [uvec (byte-array->uvec (.getBytes s "UTF-8"))]
      (write-uvec-at! world ptr uvec)
      (count uvec))))

(defn- op-read-array [world descriptor]
  (let [[ptr len] (validate-arity! :read-array (:arguments descriptor) 2)]
    (validate-pointer! :read-array ptr)
    (when-not (and (integer? len) (not (neg? len)))
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "read-array length must be a non-negative integer"
             {:length len}))
    (if (zero? len)
      (byte-array 0)
      (let [s @(:state world)
            {:keys [record offset]} (resolve-live! (:heap s) ptr len)
            uvec (subvec (:bytes record) offset (+ offset len))]
        (uvec->byte-array uvec)))))

(defn- op-write-array [world descriptor]
  (let [[ptr arr] (validate-arity! :write-array (:arguments descriptor) 2)]
    (validate-pointer! :write-array ptr)
    (when-not (bytes? arr)
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "write-array requires a byte array"
             {:value arr}))
    (let [uvec (byte-array->uvec arr)]
      (write-uvec-at! world ptr uvec)
      (count uvec))))

(defn- op-ptr->string [world descriptor]
  (let [[ptr] (validate-arity! :ptr->string (:arguments descriptor) 1)]
    (validate-pointer! :ptr->string ptr)
    (cond
      (zero? ptr) nil
      :else
      (let [s @(:state world)
            heap (:heap s)
            rec (find-containing heap ptr)]
        (when-not rec
          (pointer-miss! heap ptr))
        (let [offset (- ptr (:base rec))
              bytes (:bytes rec)
              nul (find-nul bytes offset (:size rec))]
          (when-not nul
            (fail! :jolt.sim.ffi-memory/unterminated-string
                   "ptr->string requires a NUL terminator within the allocation"
                   {:pointer ptr :base (:base rec) :size (:size rec)}))
          (decode-utf8 (subvec bytes offset nul)))))))

(defn- op-string->ptr [world descriptor]
  (let [[s] (validate-arity! :string->ptr (:arguments descriptor) 1)]
    (when-not (string? s)
      (fail! :jolt.sim.ffi-memory/invalid-argument
             "string->ptr requires a string"
             {:value s}))
    (let [body (byte-array->uvec (.getBytes s "UTF-8"))
          full (conj body 0)]
      (allocate-uvec! world full))))

(def ^:private native-ops
  {:load-library op-load-library
   :loaded? op-loaded?
   :alloc op-alloc
   :free op-free
   :read op-read
   :write op-write
   :sizeof op-sizeof
   :read-bytes op-read-bytes
   :write-bytes op-write-bytes
   :read-array op-read-array
   :write-array op-write-array
   :ptr->string op-ptr->string
   :string->ptr op-string->ptr})

;; ---- public API ---------------------------------------------------------

(defn world
  "Returns a new deterministic simulated memory world. With no argument or a
  nil argument the world uses the default LP64 little-endian config; with a
  config map every supported key is validated exactly and merged over the
  defaults."
  ([]
   (world nil))
  ([config]
   (let [cfg (validate-config (or config {}))]
     {:state (atom {:next-base (:base-address cfg)
                    :heap {}
                    :loaded-libraries #{}})
      :config cfg
      :type ::world})))

(defn handlers
  "Returns a map keyed by [:native-operation op] over all 13 ABI v2 native
  operations, compatible with jolt.sim.runtime :ffi-handlers. Each handler fn
  receives the exact descriptor and dispatches on its :arguments vector."
  [w]
  (into {}
        (map (fn [[op f]]
               [[:native-operation op]
                (fn native-handler [descriptor]
                  (f w descriptor))]))
        native-ops))

(defn snapshot
  "Returns stable plain data for every allocation, ordered by base address.
  Each entry carries the unsigned byte vector of the allocation's current
  contents. Freed allocations are retained as evidence with :freed? true."
  [w]
  (let [s @(:state w)]
    (->> (:heap s)
         vals
         (sort-by :base)
         (mapv (fn [r]
                 {:base (:base r)
                  :size (:size r)
                  :freed? (:freed? r)
                  :bytes (vec (:bytes r))})))))

(defn leaks
  "Returns live (not freed) allocation summaries ordered by base address."
  [w]
  (->> (snapshot w)
       (remove :freed?)
       (mapv (fn [r] {:base (:base r) :size (:size r)}))))

(defn clean?
  "True when there are no live allocations."
  [w]
  (every? :freed? (vals (:heap @(:state w)))))
