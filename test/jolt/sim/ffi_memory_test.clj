(ns jolt.sim.ffi-memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.ffi-memory :as fm]))

(def ^:private all-ops
  [:load-library :loaded? :alloc :free :read :write :sizeof
   :read-bytes :write-bytes :read-array :read-array! :write-array
   :borrow-byte-array :release-byte-array
   :ptr->string :string->ptr])

(def ^:private expected-handler-keys
  (set (map (fn [op] [:native-operation op]) all-ops)))

(defn- call
  "Builds the exact descriptor a sim image would hand to a handler and invokes
  it. args is the raw arguments vector passed to the operation."
  [handlers op & args]
  ((get handlers [:native-operation op])
   {:kind :native-operation
    :task 0
    :arguments (vec args)
    :operation op}))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(def ^:private record-uvec-range-var
  (resolve 'jolt.sim.ffi-memory/record-uvec-range))

;; ---- handler shape ------------------------------------------------------

(deftest handlers-cover-all-sixteen-native-operations
  (let [w (fm/world)
        h (fm/handlers w)]
    (is (= expected-handler-keys (set (keys h))))
    (doseq [k (keys h)]
      (is (ifn? (get h k))))))

(deftest world-has-zero-and-one-arity-and-default-config
  (let [w0 (fm/world)
        w1 (fm/world {})]
    (is (map? w0))
    (is (map? w1))
    (is (= (:config w0) (:config w1)))
    (is (= :little (get-in w0 [:config :byte-order])))
    (is (= 8 (get-in w0 [:config :pointer-size])))
    (is (= 8 (get-in w0 [:config :long-size])))
    (is (= :any (get-in w0 [:config :available-libraries])))
    (is (zero? (mod (get-in w0 [:config :base-address]) 8)))))

;; ---- determinism --------------------------------------------------------

(deftest allocation-bases-are-deterministic-and-monotonic
  (let [ha (fm/handlers (fm/world))
        hb (fm/handlers (fm/world))]
    (is (= (call ha :alloc 8) (call hb :alloc 8)))
    (is (= (call ha :alloc 16) (call hb :alloc 16)))
    (is (= (call ha :alloc 3) (call hb :alloc 3)))
    (let [b1 (call ha :alloc 8)
          b2 (call ha :alloc 8)
          b3 (call ha :alloc 8)]
      (is (< b1 b2 b3))
      (is (< b2 b3)))))

(deftest guard-gap-leaves-each-alignment-aligned
  ;; Every returned base is aligned to the configured alignment, and a small
  ;; allocation cannot be overwritten by an adjacent one's payload.
  (let [w (fm/world {:alignment 16})
        h (fm/handlers w)
        a (call h :alloc 8)]
    (is (zero? (mod a 16)))
    (call h :write a :uint8 0 42)
    (is (= 42 (call h :read a :uint8)))))

(deftest concurrent-handlers-linearize-allocation-write-read-and-free
  (let [world (fm/world)
        h (fm/handlers world)
        start (promise)
        workers
        (mapv
         (fn [value]
           (future
             @start
             (let [pointer (call h :alloc 8)]
               (call h :write pointer :int 0 value)
               (let [read-back (call h :read pointer :int)]
                 (call h :free pointer)
                 [pointer read-back]))))
         (range 12))]
    (deliver start true)
    (let [results (mapv deref workers)
          pointers (mapv first results)]
      (is (= (vec (range 12)) (vec (sort (map second results)))))
      (is (= 12 (count (set pointers))))
      (is (every? #(zero? (mod % 8)) pointers))
      (is (= 12 (count (fm/snapshot world))))
      (is (true? (fm/clean? world))))))

;; ---- LP64 / LLP64 widths -----------------------------------------------

(deftest lp64-and-llp64-widths-come-from-config
  (let [lp64 (fm/handlers (fm/world {:pointer-size 8 :long-size 8}))
        llp64 (fm/handlers (fm/world {:pointer-size 8 :long-size 4}))
        narrow (fm/handlers (fm/world {:pointer-size 4 :long-size 4}))]
    (is (= 8 (call lp64 :sizeof :long)))
    (is (= 8 (call lp64 :sizeof :ulong)))
    (is (= 8 (call lp64 :sizeof :pointer)))
    (is (= 8 (call lp64 :sizeof :void*)))
    (is (= 8 (call lp64 :sizeof :size_t)))
    (is (= 8 (call lp64 :sizeof :ssize_t)))
    (is (= 8 (call lp64 :sizeof :iptr)))
    (is (= 8 (call lp64 :sizeof :uptr)))
    (is (= 4 (call lp64 :sizeof :int)))
    (is (= 2 (call lp64 :sizeof :short)))
    (is (= 1 (call lp64 :sizeof :char)))
    (is (= 1 (call lp64 :sizeof :byte)))
    (is (= 8 (call lp64 :sizeof :int64)))
    (is (= 4 (call lp64 :sizeof :float)))
    (is (= 8 (call lp64 :sizeof :double)))
    ;; LLP64: 32-bit long, 64-bit pointer.
    (is (= 4 (call llp64 :sizeof :long)))
    (is (= 8 (call llp64 :sizeof :pointer)))
    ;; Narrow word: 32-bit pointer/size_t.
    (is (= 4 (call narrow :sizeof :pointer)))
    (is (= 4 (call narrow :sizeof :size_t)))))

(deftest pointer-sized-read-write-uses-configured-width
  (let [h (fm/handlers (fm/world {:pointer-size 4 :long-size 8}))
        p (call h :alloc 16)]
    (call h :write p :pointer 0 0x12345678)
    (is (= 0x12345678 (call h :read p :pointer)))
    (is (= 4 (call h :sizeof :pointer)))))

(deftest allocation-fails-before-wrapping-the-configured-pointer-width
  (let [world
        (fm/world {:pointer-size 4
                   :base-address 4294967295
                   :alignment 1})
        h (fm/handlers world)]
    (is (= 4294967295 (call h :alloc 1)))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :alloc 1)))))
    (is (= 1 (count (fm/snapshot world))))))

;; ---- both endian orders ------------------------------------------------

(deftest little-and-big-endian-byte-orders
  (let [hl (fm/handlers (fm/world {:byte-order :little}))
        hb (fm/handlers (fm/world {:byte-order :big}))
        pl (call hl :alloc 8)
        pb (call hb :alloc 8)]
    (call hl :write pl :int 0 0x01020304)
    (call hb :write pb :int 0 0x01020304)
    (is (= [0x04 0x03 0x02 0x01]
           (vec (call hl :read-array pl 4))))
    (is (= [0x01 0x02 0x03 0x04]
           (vec (call hb :read-array pb 4))))))

(deftest configured-byte-order-covers-two-and-eight-byte-scalars
  (doseq [[type value little big]
          [[:uint16 0x0102
            [0x02 0x01]
            [0x01 0x02]]
           [:uint64 0x0102030405060708
            [0x08 0x07 0x06 0x05 0x04 0x03 0x02 0x01]
            [0x01 0x02 0x03 0x04 0x05 0x06 0x07 0x08]]]]
    (let [little-handlers
          (fm/handlers (fm/world {:byte-order :little}))
          big-handlers
          (fm/handlers (fm/world {:byte-order :big}))
          size (call little-handlers :sizeof type)
          little-pointer (call little-handlers :alloc size)
          big-pointer (call big-handlers :alloc size)]
      (call little-handlers :write little-pointer type 0 value)
      (call big-handlers :write big-pointer type 0 value)
      (is (= little
             (vec (call little-handlers :read-array little-pointer size))))
      (is (= big
             (vec (call big-handlers :read-array big-pointer size))))
      (is (= value (call little-handlers :read little-pointer type)))
      (is (= value (call big-handlers :read big-pointer type))))))

;; ---- signed / unsigned aliasing ----------------------------------------

(deftest signed-and-unsigned-reads-alias-the-same-storage
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (call h :write p :uint 0 0xFFFFFFFF)
    (is (= 0xFFFFFFFF (call h :read p :uint)))
    (is (= -1 (call h :read p :int)))
    (call h :write p :int 0 -1)
    (is (= 0xFFFFFFFF (call h :read p :uint)))))

(deftest explicit-32-bit-signed-and-unsigned-alias-the-same-storage
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 4)]
    (is (= 4 (call h :sizeof :int32)))
    (is (= 4 (call h :sizeof :uint32)))
    (call h :write p :int32 0 -1)
    (is (= -1 (call h :read p :int32)))
    (is (= 0xFFFFFFFF (call h :read p :uint32)))
    (call h :write p :uint32 0 0xFFFFFFFF)
    (is (= -1 (call h :read p :int32)))))

(deftest full-64-bit-combined-range-round-trips
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 16)
        maxu64 18446744073709551615]
    (call h :write p :uint64 0 maxu64)
    (is (= maxu64 (call h :read p :uint64)))
    (is (= -1 (call h :read p :int64)))
    (call h :write p :ulong 0 maxu64)
    (is (= maxu64 (call h :read p :ulong)))))

(deftest integer-write-range-is-the-combined-chez-storage-range
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)
        ok-max 255
        ok-min -128]
    ;; 255 fits the combined 1-byte range and is stored modulo 256.
    (call h :write p :uint8 0 ok-max)
    (is (= ok-max (call h :read p :uint8)))
    ;; -128 fits as the signed minimum.
    (call h :write p :int8 0 ok-min)
    (is (= ok-min (call h :read p :int8)))
    ;; Out-of-range values are rejected.
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :write p :uint8 0 256)))))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :write p :int8 0 -129)))))))

(deftest char-read-write-stores-and-returns-a-character
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (call h :write p :char 0 \A)
    (is (char? (call h :read p :char)))
    (is (= \A (call h :read p :char)))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :write p :char 0 65)))))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :write p :char 0 \u0100)))))))

;; ---- interior pointers and boundary rejection --------------------------

(deftest interior-pointers-resolve-for-read-and-write
  (let [h (fm/handlers (fm/world))
        base (call h :alloc 16)
        interior (+ base 4)]
    (call h :write interior :int 0 99)
    (is (= 99 (call h :read interior :int)))
    (is (= 99 (call h :read base :int 4)))
    (call h :write base :int 4 -27)
    (is (= -27 (call h :read interior :int)))))

(deftest boundary-access-is-rejected-without-mutation
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 4)]
    (call h :write p :int 0 0x01020304)
    ;; One byte past the end: out-of-bounds.
    (is (= :jolt.sim.ffi-memory/out-of-bounds
           (:type (ex-data-of #(call h :read (+ p 1) :int)))))
    (is (= :jolt.sim.ffi-memory/out-of-bounds
           (:type (ex-data-of #(call h :write (+ p 1) :int 0 0)))))
    ;; The failed write must not have mutated the allocation.
    (is (= 0x01020304 (call h :read p :int)))))

(deftest scalar-offset-cannot-jump-into-an-adjacent-allocation
  (let [h (fm/handlers (fm/world))
        a (call h :alloc 4)
        b (call h :alloc 4)]
    (call h :write b :int 0 22)
    (is (= :jolt.sim.ffi-memory/out-of-bounds
           (:type
            (ex-data-of
             #(call h :write a :int (- b a) 33)))))
    (is (= :jolt.sim.ffi-memory/out-of-bounds
           (:type
            (ex-data-of
             #(call h :read a :int (- b a))))))
    (is (= 22 (call h :read b :int)))))

(deftest signed-scalar-offsets-stay-within-the-original-allocation
  (let [h (fm/handlers (fm/world))
        base (call h :alloc 8)
        interior (+ base 4)]
    (call h :write base :int 0 17)
    (is (= 17 (call h :read interior :int -4)))
    (call h :write interior :int -4 29)
    (is (= 29 (call h :read base :int)))
    (is (= :jolt.sim.ffi-memory/out-of-bounds
           (:type (ex-data-of #(call h :read base :int -1)))))))

;; ---- double free, use-after-free, unknown pointer ----------------------

(deftest double-free-is-rejected
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (call h :free p)
    (is (= :jolt.sim.ffi-memory/double-free
           (:type (ex-data-of #(call h :free p)))))))

(deftest interior-free-is-invalid-and-unknown-pointer-is-unknown
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (is (= :jolt.sim.ffi-memory/invalid-free
           (:type (ex-data-of #(call h :free (+ p 1))))))
    (is (= :jolt.sim.ffi-memory/unknown-pointer
           (:type (ex-data-of #(call h :free 0xdeadbeef)))))))

(deftest use-after-free-is-distinct-from-an-unknown-pointer
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (call h :write p :int 0 7)
    (call h :free p)
    (is (= :jolt.sim.ffi-memory/use-after-free
           (:type (ex-data-of #(call h :read p :int)))))
    (is (= :jolt.sim.ffi-memory/use-after-free
           (:type (ex-data-of
                   #(call h :write-array p (byte-array [1]))))))
    (is (= :jolt.sim.ffi-memory/unknown-pointer
           (:type (ex-data-of #(call h :read 0xdeadbeef :int)))))))

(deftest zero-pointer-free-is-a-no-op
  (let [h (fm/handlers (fm/world))]
    (is (nil? (call h :free 0)))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :free nil)))))))

;; ---- zero-length operations --------------------------------------------

(deftest zero-length-operations-return-empty-without-dereference
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (is (= "" (call h :read-bytes p 0)))
    (is (= 0 (alength (call h :read-array p 0))))
    (is (= 0 (call h :write-array p (byte-array 0))))
    ;; Even an invalid pointer with zero length does not dereference.
    (is (= "" (call h :read-bytes 0xdeadbeef 0)))
    (is (= 0 (alength (call h :read-array 0xdeadbeef 0))))))

(deftest alloc-rejects-non-positive-sizes
  (let [h (fm/handlers (fm/world))]
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :alloc 0)))))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :alloc -1)))))))

;; ---- copy-in / copy-out ------------------------------------------------

(deftest write-array-copies-incoming-bytes
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 4)
        src (byte-array [10 20 30 40])]
    (is (= 4 (call h :write-array p src)))
    (aset src 0 99) ; mutate the caller's array after the call
    (is (= [10 20 30 40] (vec (call h :read-array p 4))))))

(deftest ranged-write-array-copies-the-exact-signed-byte-window
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 6)
        src (byte-array [9 -128 -1 0 127 8])]
    (call h :write-array p (byte-array [1 1 1 1 1 1]))
    (is (= 4 (call h :write-array p src 1 4)))
    (is (= [-128 -1 0 127 1 1]
           (vec (call h :read-array p 6))))
    (is (= [9 -128 -1 0 127 8] (vec src))
        "the source is only snapshotted, never mutated")))

(deftest ranged-write-array-admits-an-exact-empty-tail-without-dereference
  (let [h (fm/handlers (fm/world))
        src (byte-array [1 2 3])]
    (is (= 0 (call h :write-array 0 src 3 0)))
    (is (= [1 2 3] (vec src)))))

(deftest ranged-write-array-rejects-before-any-modeled-memory-mutation
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 4)
        src (byte-array [10 20 30 40])]
    (call h :write-array p (byte-array [7 7 7 7]))
    (doseq [[arguments expected-type]
            [[[p "not-bytes" 0 1]
              :jolt.sim.ffi-memory/invalid-argument]
             [[p src 1.5 1]
              :jolt.sim.ffi-memory/invalid-argument]
             [[p src 0 1.5]
              :jolt.sim.ffi-memory/invalid-argument]
             [[p src -1 1]
              :jolt.sim.ffi-memory/out-of-bounds]
             [[p src 3 2]
              :jolt.sim.ffi-memory/out-of-bounds]
             [[(+ p 3) src 0 2]
              :jolt.sim.ffi-memory/out-of-bounds]]]
      (is (= expected-type
             (:type (ex-data-of
                     #(apply call h :write-array arguments))))
          (pr-str arguments))
      (is (= [7 7 7 7] (vec (call h :read-array p 4)))
          "a rejected call must not partially write modeled memory")
      (is (= [10 20 30 40] (vec src))
          "a rejected call must not mutate the source"))))

(deftest read-array-returns-a-fresh-byte-array
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 4)]
    (call h :write-array p (byte-array [1 2 3 4]))
    (let [a (call h :read-array p 4)
          b (call h :read-array p 4)]
      (is (not (identical? a b)))
      (aset a 0 99)
      (is (= 1 (first (vec b)))))))

(deftest read-array!-copies-modeled-bytes-into-the-live-destination
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (call h :write-array p (byte-array [10 -56 -1 40 50 60 70 80]))
    (let [dest (byte-array 16)
          copied (call h :read-array! p 3 dest 4)]
      (is (= 3 copied))
      (is (= [0 0 0 0 10 -56 -1 0 0 0 0 0 0 0 0 0] (vec dest))
          "the requested bytes are written at the destination offset and the
          rest of the live array is untouched"))
    ;; A zero-length read-array! is a no-op return that dereferences nothing
    ;; and leaves the destination unchanged.
    (let [dest (byte-array [99 99 99 99])]
      (is (= 0 (call h :read-array! p 0 dest 0)))
      (is (= [99 99 99 99] (vec dest))))))

(deftest read-array!-uses-the-same-fail-closed-validation-as-read-array
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 4)]
    (call h :write-array p (byte-array [1 2 3 4]))
    ;; destination must be a byte array
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :read-array! p 1 "not-bytes" 0)))))
    ;; destination range exceeds the live array
    (let [small (byte-array 2)]
      (is (= :jolt.sim.ffi-memory/out-of-bounds
             (:type (ex-data-of #(call h :read-array! p 3 small 0)))))
      (is (= :jolt.sim.ffi-memory/out-of-bounds
             (:type (ex-data-of #(call h :read-array! p 2 small 1)))))
      (is (= [0 0] (vec small))
          "a failed read-array! must not mutate the destination"))
    ;; source bounds are enforced with the same provenance as read-array.
    (let [dest (byte-array [9 9 9 9 9 9 9 9])]
      (is (= :jolt.sim.ffi-memory/out-of-bounds
             (:type (ex-data-of #(call h :read-array! (+ p 1) 4 dest 0)))))
      (is (= [9 9 9 9 9 9 9 9] (vec dest))))
    ;; pointer must address a live allocation
    (let [dest (byte-array [8 8 8 8])]
      (is (= :jolt.sim.ffi-memory/unknown-pointer
             (:type (ex-data-of #(call h :read-array! 0xdeadbeef 1 dest 0)))))
      (is (= [8 8 8 8] (vec dest))))))

(deftest read-array!-writes-through-a-live-byte-array-loan-window
  (let [w (fm/world)
        h (fm/handlers w)
        arr (byte-array [0 0 0 0 0 0 0 0])
        p (call h :borrow-byte-array arr 0 4)
        src (call h :alloc 4)]
    (call h :write-array src (byte-array [11 22 33 44]))
    ;; The modeled write goes into the live borrowed window, aliasing arr.
    (is (= 4 (call h :read-array! src 4 arr 0)))
    (is (= [11 22 33 44 0 0 0 0] (vec arr)))
    (is (= [11 22 33 44] (vec (call h :read-array p 4))))
    (call h :release-byte-array p)
    (call h :free src)
    (is (true? (fm/clean? w)))))

;; ---- scoped live byte-array loans --------------------------------------

(deftest loan-aliases-one-live-array-window-in-both-directions
  (let [w (fm/world)
        h (fm/handlers w)
        arr (byte-array [10 20 30 40])
        p (call h :borrow-byte-array arr 1 2)]
    (is (pos? p))
    (is (= 20 (call h :read p :uint8)))
    (call h :write p :uint8 0 77)
    (is (= [10 77 30 40] (vec arr)))
    ;; Ordinary Jolt mutation is visible to the next modeled native access.
    (aset arr 2 99)
    (is (= 99 (call h :read (+ p 1) :uint8)))
    ;; Bulk writes snapshot their source and mutate only the loan window.
    (is (= 2 (call h :write-array p (byte-array [7 8]))))
    (is (= [10 7 8 40] (vec arr)))
    (is (= [7 8] (vec (call h :read-array p 2))))
    (is (= [{:base p :size 2}] (fm/leaks w)))
    (call h :release-byte-array p)
    (is (true? (fm/clean? w)))
    (is (= :jolt.sim.ffi-memory/use-after-release
           (:type (ex-data-of #(call h :read p :uint8)))))
    (is (= [7 8] (:bytes (first (fm/snapshot w)))))))

(deftest loan-reads-materialize-only-the-requested-live-range
  (let [w (fm/world)
        h (fm/handlers w)
        arr (byte-array 4096)
        _ (aset arr 4095 123)
        _ (aset arr 2048 7)
        _ (aset arr 2049 8)
        _ (aset arr 2050 9)
        p (call h :borrow-byte-array arr 0 4096)
        original @record-uvec-range-var
        ranges (atom [])]
    (with-redefs-fn
      {record-uvec-range-var
       (fn [record offset length]
         (swap! ranges conj [offset length])
         (original record offset length))}
      #(do
         (is (= 123 (call h :read (+ p 4095) :uint8)))
         (is (= [7 8 9]
                (vec (call h :read-array (+ p 2048) 3))))))
    (is (= [[4095 1] [2048 3]] @ranges))
    (call h :release-byte-array p)
    (is (true? (fm/clean? w)))))

(deftest empty-and-nested-loans-have-distinct-balanced-lifetimes
  (let [w (fm/world)
        h (fm/handlers w)
        arr (byte-array [1 2 3 4])
        empty (call h :borrow-byte-array arr 4 0)
        outer (call h :borrow-byte-array arr 0 4)
        inner (call h :borrow-byte-array arr 1 2)]
    (is (every? pos? [empty outer inner]))
    (is (= 3 (count (set [empty outer inner]))))
    (call h :write inner :uint8 0 55)
    (is (= 55 (call h :read (+ outer 1) :uint8)))
    (call h :release-byte-array inner)
    ;; Releasing one alias does not retire the independent outer loan.
    (is (= 55 (call h :read (+ outer 1) :uint8)))
    (is (= :jolt.sim.ffi-memory/use-after-release
           (:type (ex-data-of #(call h :read inner :uint8)))))
    (call h :release-byte-array empty)
    (call h :release-byte-array outer)
    (is (true? (fm/clean? w)))))

(deftest loan-validation-and-release-ownership-fail-closed
  (let [w (fm/world)
        h (fm/handlers w)
        arr (byte-array [1 2 3])
        owned (call h :alloc 3)
        loan (call h :borrow-byte-array arr 0 3)]
    (doseq [args [["not-bytes" 0 0]
                  [arr -1 1]
                  [arr 0 -1]
                  [arr 2 2]
                  [arr 4 0]]]
      (is (contains?
           #{:jolt.sim.ffi-memory/invalid-argument
             :jolt.sim.ffi-memory/out-of-bounds}
           (:type
            (ex-data-of
             #(apply call h :borrow-byte-array args))))))
    (is (= :jolt.sim.ffi-memory/invalid-free
           (:type (ex-data-of #(call h :free loan)))))
    (is (= :jolt.sim.ffi-memory/invalid-release
           (:type (ex-data-of #(call h :release-byte-array owned)))))
    (is (= :jolt.sim.ffi-memory/invalid-release
           (:type (ex-data-of #(call h :release-byte-array (inc loan))))))
    (call h :release-byte-array loan)
    (is (= :jolt.sim.ffi-memory/double-release
           (:type (ex-data-of #(call h :release-byte-array loan)))))
    (is (= :jolt.sim.ffi-memory/unknown-pointer
           (:type (ex-data-of #(call h :release-byte-array 999999)))))
    (call h :free owned)
    (is (true? (fm/clean? w)))))

;; ---- strings -----------------------------------------------------------

(deftest string-roundtrip-and-ownership
  (let [w (fm/world)
        h (fm/handlers w)
        s "hello \u00e9"
        p (call h :string->ptr s)] ; UTF-8 multibyte
    (is (= s (call h :ptr->string p)))
    (call h :free p)
    (is (true? (fm/clean? w))))) ; freeing the owned allocation cleans the world

(deftest ptr->string-returns-nil-for-zero-and-detects-unterminated
  (let [h (fm/handlers (fm/world))]
    (is (nil? (call h :ptr->string 0)))
    (let [p (call h :alloc 4)]
      (call h :write-array p (byte-array [0x41 0x42 0x43 0x44])) ; no NUL
      (is (= :jolt.sim.ffi-memory/unterminated-string
             (:type (ex-data-of #(call h :ptr->string p))))))))

(deftest ptr->string-rejects-invalid-utf8
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 2)]
    (call h :write-array p (byte-array [0xFF 0]))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :ptr->string p)))))))

(deftest read-bytes-decodes-utf8-with-latin1-fallback
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 8)]
    (is (= 5 (call h :write-bytes p "caf\u00e9")))
    (is (= "caf\u00e9" (call h :read-bytes p 5)))
    ;; 0xFF alone is invalid UTF-8: falls back to ISO-8859-1 (U+00FF).
    (call h :write-array p (byte-array [0xFF 0xFE 0xFD 0xFC 0xFB 0 0 0]))
    (let [s (call h :read-bytes p 5)]
      (is (= 5 (count s)))
      (is (= [255 254 253 252 251] (mapv int s))))))

;; ---- library policy ----------------------------------------------------

(deftest process-load-is-always-available
  (let [h (fm/handlers (fm/world))]
    (is (nil? (call h :load-library nil)))
    (is (nil? (call h :load-library)))))

(deftest any-library-policy-loads-named-libraries
  (let [h (fm/handlers (fm/world))]
    (is (nil? (call h :load-library "libfoo")))
    (is (true? (call h :loaded? "libfoo")))
    (is (false? (call h :loaded? "libbar")))))

(deftest host-rest-argument-operations-ignore-extra-arguments
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 4)]
    (is (nil? (call h :load-library "libfoo" :ignored)))
    (is (true? (call h :loaded? "libfoo")))
    (call h :write p :int 0 41)
    (is (= 41 (call h :read p :int 0 :ignored)))))

(deftest set-policy-admits-membership-and-rejects-others
  (let [h (fm/handlers (fm/world {:available-libraries #{"liba" "libb"}}))]
    (is (nil? (call h :load-library "liba")))
    (is (true? (call h :loaded? "liba")))
    (is (= :jolt.sim.ffi-memory/unavailable-library
           (:type (ex-data-of #(call h :load-library "libx")))))
    (is (false? (call h :loaded? "libx")))))

;; ---- snapshot / leaks / clean? -----------------------------------------

(deftest snapshot-and-leaks-are-ordered-by-address
  (let [w (fm/world)
        h (fm/handlers w)
        a (call h :alloc 4)
        b (call h :alloc 4)
        c (call h :alloc 4)]
    (call h :write-array a (byte-array [1 2 3 4]))
    (call h :free b)
    (let [snap (fm/snapshot w)]
      (is (= [a b c] (mapv :base snap)))
      (is (= [false true false] (mapv :freed? snap)))
      (is (= [1 2 3 4] (:bytes (first snap)))))
    (let [leaks (fm/leaks w)]
      (is (= [a c] (mapv :base leaks)))
      (is (= [{:base a :size 4} {:base c :size 4}] leaks)))
    (is (false? (fm/clean? w)))
    (call h :free a)
    (call h :free c)
    (is (true? (fm/clean? w)))))

;; ---- config validation -------------------------------------------------

(deftest invalid-config-is-typed
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type (ex-data-of #(fm/world {:surprise 1})))))
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type (ex-data-of #(fm/world {:byte-order :sideways})))))
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type (ex-data-of #(fm/world {:pointer-size 16})))))
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type (ex-data-of #(fm/world {:alignment 6})))))
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type (ex-data-of #(fm/world {:base-address 0})))))
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type
          (ex-data-of
           #(fm/world {:pointer-size 4
                       :base-address 4294967296
                       :alignment 1})))))
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type (ex-data-of #(fm/world {:available-libraries ["liba"]})))))
  (is (= :jolt.sim.ffi-memory/invalid-config
         (:type (ex-data-of #(fm/world "not a map"))))))

;; ---- scalar type errors ------------------------------------------------

(deftest float-and-double-reads-and-writes-fail-closed
  (let [h (fm/handlers (fm/world))
        p (call h :alloc 16)]
    (is (= :jolt.sim.ffi-memory/unsupported-scalar-type
           (:type (ex-data-of #(call h :read p :float)))))
    (is (= :jolt.sim.ffi-memory/unsupported-scalar-type
           (:type (ex-data-of #(call h :write p :double 0 1.0)))))))

(deftest sizeof-rejects-invalid-types
  (let [h (fm/handlers (fm/world))]
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :sizeof :string)))))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :sizeof :void)))))
    (is (= :jolt.sim.ffi-memory/invalid-argument
           (:type (ex-data-of #(call h :sizeof :nope)))))))

;; ---- hybrid handlers ------------------------------------------------------

;; Both are private in jolt.sim.runtime; resolved the same way
;; record-uvec-range-var is above, matching this file's own precedent for
;; reaching one namespace's internals from a test in another.
(def ^:private decode-handler-result-var
  (resolve 'jolt.sim.runtime/decode-handler-result!))

(def ^:private validate-hybrid-classification-var
  (resolve 'jolt.sim.runtime/validate-hybrid-classification!))

(defn- classify
  "Invokes one hybrid handler and decodes+validates its classified result
  exactly as jolt.sim.runtime's :hybrid routing would, returning a plain
  {:kind :value :span?} map."
  [handlers op & args]
  (let [descriptor {:kind :native-operation :task 0
                     :arguments (vec args) :operation op}
        result ((get handlers [:native-operation op]) descriptor)]
    (validate-hybrid-classification-var
     descriptor
     (decode-handler-result-var :hybrid descriptor (fn [_]) result))))

(deftest hybrid-handlers-cover-all-sixteen-native-operations
  (let [w (fm/world)
        h (fm/hybrid-handlers w)]
    (is (= expected-handler-keys (set (keys h))))
    (doseq [k (keys h)]
      (is (ifn? (get h k))))))

(deftest hybrid-handlers-classify-scalar-nil-string-and-byte-array-results-as-substitute
  (let [w (fm/world)
        h (fm/hybrid-handlers w)
        p (:value (classify h :alloc 8))]
    (is (= {:kind :substitute :value nil} (classify h :load-library "libfoo")))
    (is (= {:kind :substitute :value true} (classify h :loaded? "libfoo")))
    (is (= {:kind :substitute :value false} (classify h :loaded? "libbar")))
    (is (= {:kind :substitute :value 8} (classify h :sizeof :long)))
    (is (= {:kind :substitute :value nil} (classify h :write p :int 0 42)))
    (is (= {:kind :substitute :value 42} (classify h :read p :int)))
    (is (= {:kind :substitute :value 5} (classify h :write-bytes p "café")))
    (is (= {:kind :substitute :value "café"} (classify h :read-bytes p 5)))
    (let [ra (classify h :read-array p 5)]
      (is (= :substitute (:kind ra)))
      (is (= [99 97 102 -61 -87] (vec (:value ra)))))
    (let [dest (byte-array 5)]
      (is (= {:kind :substitute :value 5}
             (classify h :read-array! p 5 dest 0)))
      (is (= [99 97 102 -61 -87] (vec dest))))
    (is (= {:kind :substitute :value 5}
           (classify h :write-array p (byte-array [1 2 3 4 5]))))
    (is (= {:kind :substitute :value nil} (classify h :free p)))))

(deftest hybrid-handlers-null-pointer-results-remain-substitute
  (let [w (fm/world)
        h (fm/hybrid-handlers w)]
    (is (= {:kind :substitute :value nil} (classify h :free 0)))
    (is (= {:kind :substitute :value nil} (classify h :ptr->string 0)))
    (let [p (:value (classify h :alloc 8))]
      (classify h :write p :pointer 0 0)
      (is (= {:kind :substitute :value 0} (classify h :read p :pointer)))
      (classify h :free p))))

(deftest hybrid-handlers-classify-positive-fake-pointers-as-modeled-resource-with-exact-span
  (let [w (fm/world)
        h (fm/hybrid-handlers w)
        alloc-result (classify h :alloc 12)]
    (is (= :modeled-resource (:kind alloc-result)))
    (is (pos? (:value alloc-result)))
    (is (= 12 (:span alloc-result)))
    (let [arr (byte-array [1 2 3 4])
          borrow-result (classify h :borrow-byte-array arr 0 4)]
      (is (= :modeled-resource (:kind borrow-result)))
      (is (pos? (:value borrow-result)))
      (is (= 4 (:span borrow-result)))
      (classify h :release-byte-array (:value borrow-result)))
    ;; A zero-length loan still reserves one live fake address.
    (let [empty-borrow (classify h :borrow-byte-array (byte-array 0) 0 0)]
      (is (= :modeled-resource (:kind empty-borrow)))
      (is (pos? (:value empty-borrow)))
      (is (= 1 (:span empty-borrow)))
      (classify h :release-byte-array (:value empty-borrow)))
    (let [str-result (classify h :string->ptr "hello é")]
      (is (= :modeled-resource (:kind str-result)))
      (is (pos? (:value str-result)))
      (is (= 9 (:span str-result)))
      (classify h :free (:value str-result)))
    (let [p (:value alloc-result)]
      (classify h :write p :pointer 0 p)
      (let [read-back (classify h :read p :pointer)]
        (is (= :modeled-resource (:kind read-back)))
        (is (= p (:value read-back))))
      (classify h :free p))))

(deftest hybrid-handlers-reuse-the-model-exactly-once-per-call
  (let [w (fm/world)
        h (fm/hybrid-handlers w)]
    (classify h :alloc 8)
    (is (= 1 (count (fm/snapshot w)))
        "one hybrid :alloc call must record exactly one allocation")
    (let [arr (byte-array [1 2 3 4])
          borrowed (classify h :borrow-byte-array arr 0 4)]
      (is (= 2 (count (fm/snapshot w)))
          "one hybrid :borrow-byte-array call must record exactly one more allocation")
      (classify h :release-byte-array (:value borrowed)))))
