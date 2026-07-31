(ns jolt.sim.fixtures.ffi-roundtrip
  "Ordinary Jolt FFI code used unchanged in real and simulated executions.

  This namespace deliberately has no dependency on jolt.sim. Its caller
  chooses whether the standard jolt.ffi operations reach Chez/native memory or
  an installed ABI v2 controller."
  (:require [jolt.ffi :as ffi]))

(defn exercise-memory
  "Exercises process-symbol loading, scalar offsets, an interior byte buffer,
  an owned C string, exact copies, and balanced cleanup through jolt.ffi."
  []
  (ffi/load-library)
  (let [int-size (ffi/sizeof :int)
        pointer-size (ffi/sizeof :pointer)
        payload (byte-array [0 127 128 255 10])
        memory (ffi/alloc (+ int-size (alength payload)))]
    (try
      (let [text-pointer (ffi/string->ptr "native \u00e9")]
        (try
          (ffi/write memory :int 0 -1234567)
          (let [written
                (ffi/write-array (+ memory int-size) payload)]
            {:integer (ffi/read memory :int 0)
             :bytes (vec
                     (ffi/read-array
                      (+ memory int-size) (alength payload)))
             :text (ffi/ptr->string text-pointer)
             :written written
             :int-size int-size
             :pointer-size pointer-size})
          (finally
            (ffi/free text-pointer))))
      (finally
        (ffi/free memory)))))
