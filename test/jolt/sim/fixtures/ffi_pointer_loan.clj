(ns jolt.sim.fixtures.ffi-pointer-loan
  "Ordinary Jolt FFI code used unchanged in real and simulated executions.

  This namespace deliberately has no dependency on jolt.sim. Its caller
  chooses whether the standard jolt.ffi operations reach real native memory or
  the installed current controller with FFI descriptor-version 4."
  (:require [jolt.ffi :as ffi]))

(defn exercise-loan
  "Loans one live byte-array window, mutates it through modeled/native memory,
  and proves an ordinary array mutation is visible through the same pointer."
  []
  (let [bytes (byte-array [10 20 30 40])
        observed
        (ffi/with-byte-array-pointer
          bytes 1 2
          (fn [pointer length]
            (ffi/write pointer :uint8 0 201)
            (aset bytes 2 202)
            {:length length
             :native-read (ffi/read (+ pointer 1) :uint8 0)}))]
    (assoc observed :bytes (vec bytes))))

(defn exercise-throwing-loan
  "Throws from the ordinary callback after a native mutation. The scoped FFI
  implementation must release the loan before this function catches the body
  exception."
  []
  (let [bytes (byte-array [9 8 7])]
    (try
      (ffi/with-byte-array-pointer
        bytes
        (fn [pointer _]
          (ffi/write pointer :uint8 1 77)
          (throw (ex-info "fixture pointer-loan failure"
                          {:type :fixture/pointer-loan-failure}))))
      {:caught :missing}
      (catch Exception error
        {:caught (:type (ex-data error))
         :bytes (vec bytes)}))))
