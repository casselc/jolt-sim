(ns jolt.sim.journal-writer-test
  "Executable companions for the writer half of proofs/journal-wal: the
  bounded full-write loop, consecutive-EINTR policy, absorbing failure,
  durability boundary, and failure-isolation catch premises, all against
  the real synchronous writer over scripted in-memory seams."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.journal :as jrn]
            [jolt.sim.journal-writer :as jw]))

;; ---- helpers ---------------------------------------------------------------

(defn- ba
  "Builds a byte-array from unsigned byte values (0..255)."
  [& vs]
  (byte-array (map unchecked-byte vs)))

(defn- bytes=
  "Content equality for byte arrays. Ordinary = on byte arrays is reference
  equality and is never used for contents."
  [a b]
  (and (bytes? a)
       (bytes? b)
       (= (alength a) (alength b))
       (loop [i 0]
         (cond
           (= i (alength a)) true
           (not= (aget a i) (aget b i)) false
           :else (recur (inc i))))))

(defn- get-u32
  "Test-local unsigned big-endian u32 read."
  [src off]
  (reduce (fn [v i]
            (+ (* v 256) (bit-and (aget src (+ off i)) 0xff)))
          0
          (range 4)))

(defn- slice [src off end]
  (java.util.Arrays/copyOfRange src off end))

(defn- cat-bs [& parts]
  (let [total (reduce + 0 (map alength parts))
        out (byte-array total)]
    (loop [ps (seq parts)
           off 0]
      (when ps
        (let [p (first ps)
              n (alength p)]
          (dotimes [i n]
            (aset out (+ off i) (aget p i)))
          (recur (next ps) (+ off n)))))
    out))

(defn- pure-data?
  "Recursive structural check: only scalars and keyword-keyed maps, so no
  functions, byte arrays, throwables, host objects, or mutable aliases."
  [x]
  (cond
    (nil? x) true
    (keyword? x) true
    (string? x) true
    (number? x) true
    (boolean? x) true
    (char? x) true
    (map? x) (and (every? keyword? (keys x))
                  (every? pure-data? (vals x)))
    :else false))

;; ---- fixtures ---------------------------------------------------------------

(def ^:private run-id
  ;; Deliberately includes 0x80 and 0xff high bytes.
  (ba 0xde 0xad 0xbe 0xef 0x80 0xff 0x00 0x01
      0x7f 0x81 0xfe 0x02 0x03 0x04 0x05 0x06))

(defn- mock-sink
  "Scripted in-memory sink with call recording. Consumes one write-script
  element per write! call:

    n                 integer: {:status :written :count n}
    {:count x}        {:status :written :count x} (x arbitrary)
    :eintr            {:status :eintr}
    {:error code}     {:status :error :code code}
    {:throw t}        throws t
    {:raw x}          returns x as-is (a malformed result)

  For a :written result with an integer count in 0..remaining the sink
  records exactly count bytes copied from the frame at the requested
  offset. Calls beyond the script end are recorded with :step nil and
  return {:status :error :code :script-exhausted}."
  [write-script]
  (let [chunks (atom [])
        calls (atom [])
        steps (atom (seq write-script))
        flushes (atom 0)
        syncs (atom 0)]
    {:write! (fn [frame offset remaining]
               (let [step (first @steps)]
                 (swap! steps next)
                 (swap! calls conj {:offset offset :remaining remaining :step step})
                 (cond
                   (integer? step)
                   (do
                     (when (<= 0 step remaining)
                       (swap! chunks conj
                              (java.util.Arrays/copyOfRange frame offset (+ offset step))))
                     {:status :written :count step})

                   (and (map? step) (contains? step :count))
                   (let [c (:count step)]
                     (when (and (integer? c) (<= 0 c remaining))
                       (swap! chunks conj
                              (java.util.Arrays/copyOfRange frame offset (+ offset c))))
                     {:status :written :count c})

                   (= :eintr step)
                   {:status :eintr}

                   (and (map? step) (contains? step :error))
                   {:status :error :code (:error step)}

                   (and (map? step) (contains? step :throw))
                   (throw (:throw step))

                   (and (map? step) (contains? step :raw))
                   (:raw step)

                   :else
                   {:status :error :code :script-exhausted})))
     :flush! (fn [] (swap! flushes inc) {:status :ok})
     :sync! (fn [] (swap! syncs inc) {:status :ok})
     :bytes (fn [] (apply cat-bs (seq @chunks)))
     :calls (fn [] @calls)
     :flushes (fn [] @flushes)
     :syncs (fn [] @syncs)}))

(defn- process-opts
  "Process-crash writer opts over the mock; no :sync! key at all."
  ([m] (process-opts m {}))
  ([m overrides]
   (merge {:run-id run-id
           :max-payload 8
           :durability :process-crash
           :write! (:write! m)
           :flush! (:flush! m)}
          overrides)))

(defn- power-opts
  "Power-durability writer opts over the mock."
  ([m] (power-opts m {}))
  ([m overrides]
   (merge {:run-id run-id
           :max-payload 8
           :durability :power
           :write! (:write! m)
           :flush! (:flush! m)
           :sync! (:sync! m)}
          overrides)))

(defn- good-base
  "Minimal valid opts with trivial seams, for config-validation cases."
  []
  {:run-id run-id
   :max-payload 8
   :durability :process-crash
   :write! (fn [_frame _offset remaining] {:status :written :count remaining})
   :flush! (fn [] {:status :ok})})

;; ---- 1/2. header construction ------------------------------------------------

(deftest header-bytes-equal-encode-header-with-process-status
  (let [m (mock-sink [36])
        w (jw/make-writer (process-opts m))
        status (jw/writer-status w)
        expected (jrn/encode-header {:run-id run-id :max-payload 8})]
    (is (bytes= expected ((:bytes m))))
    (is (= [{:offset 0 :remaining 36 :step 36}] ((:calls m))))
    (is (= :healthy (:health status)))
    (is (nil? (:failure status)))
    (is (= 0 (:sequence status)))
    (is (= (get-u32 expected 32) (:previous-crc32c status)))
    (is (= 36 (:accepted-end status)))
    (is (= 0 (:synced-end status)))
    (is (= :process-crash (:durability status)))
    (is (not (contains? (:config w) :run-id))
        "the caller-owned run-id array is not retained after header creation")
    (is (= 1 ((:flushes m))))
    (is (= 0 ((:syncs m))))))

(deftest power-header-runs-write-flush-sync-in-order
  (let [m (mock-sink [36])
        events (atom [])
        w (jw/make-writer
           (assoc (power-opts m)
                  :write! (fn [f o r]
                            (swap! events conj :write)
                            ((:write! m) f o r))
                  :flush! (fn [] (swap! events conj :flush) {:status :ok})
                  :sync! (fn [] (swap! events conj :sync) {:status :ok})))
        status (jw/writer-status w)]
    (is (= [:write :flush :sync] @events))
    (is (= :healthy (:health status)))
    (is (= 36 (:accepted-end status)))
    (is (= 36 (:synced-end status)))))

;; ---- 3/4. record framing -----------------------------------------------------

(deftest one-append-bytes-equal-encode-segment-with-chain-status
  (let [payload (ba 9 8 7)
        m (mock-sink [36 31])
        w (jw/make-writer (power-opts m))
        status (jw/append! w {:kind 5 :flags 3 :payload payload})
        expected (jrn/encode-segment {:run-id run-id
                                      :max-payload 8
                                      :records [{:kind 5 :flags 3
                                                 :payload payload}]})]
    (is (bytes= expected ((:bytes m))))
    (is (= :healthy (:health status)))
    (is (= 1 (:sequence status)))
    ;; The chain links to exactly this record's trailer CRC.
    (is (= (get-u32 expected (+ 36 24 3)) (:previous-crc32c status)))
    (is (= (alength expected) (:accepted-end status)))
    (is (= (alength expected) (:synced-end status)))))

(deftest partial-writes-advance-exactly-without-skip-or-dup
  (let [payload (ba 1 2 3 4)
        m (mock-sink [36 1 2 29])
        w (jw/make-writer (process-opts m))
        status (jw/append! w {:payload payload})
        calls ((:calls m))]
    (is (= :healthy (:health status)))
    ;; Exact requested offsets/lengths for counts [1 2 remaining]: no byte
    ;; skipped or duplicated.
    (is (= [{:offset 0 :remaining 36 :step 36}
            {:offset 0 :remaining 32 :step 1}
            {:offset 1 :remaining 31 :step 2}
            {:offset 3 :remaining 29 :step 29}]
           calls))
    (is (bytes= (jrn/encode-segment {:run-id run-id
                                     :max-payload 8
                                     :records [{:payload payload}]})
                ((:bytes m))))
    (is (= 68 (:accepted-end status)))))

(deftest post-write-frame-mutation-cannot-change-the-crc-chain
  (let [chunks (atom [])
        write! (fn [frame offset remaining]
                 ;; Model an adapter that first consumes the requested bytes,
                 ;; then scrubs the borrowed buffer before returning. The
                 ;; writer must have captured its chain metadata beforehand.
                 (swap! chunks conj
                        (java.util.Arrays/copyOfRange
                         frame offset (+ offset remaining)))
                 (dotimes [i (alength frame)]
                   (aset frame i (unchecked-byte 0)))
                 {:status :written :count remaining})
        w (jw/make-writer {:run-id run-id
                           :max-payload 8
                           :durability :process-crash
                           :write! write!
                           :flush! (fn [] {:status :ok})})]
    (jw/append! w {:kind 1 :payload (ba 1 2 3)})
    (let [status (jw/append! w {:kind 2 :payload (ba 4 5 6 7)})
          bytes (apply cat-bs (seq @chunks))
          recovered (jrn/recover bytes)]
      (is (= :healthy (:health status)))
      (is (nil? (:failure-reason recovered)))
      (is (= [0 1] (mapv :sequence (:records recovered))))
      (is (= (:previous-crc32c status)
             (:crc32c (last (:records recovered)))))
      (is (= (alength bytes) (:accepted-end status))))))

;; ---- 5/6. consecutive-EINTR policy --------------------------------------------

(deftest eintr-at-start-and-between-writes-with-progress-reset
  (testing "EINTR as the first results of the whole segment (header frame)"
    (let [m (mock-sink [:eintr :eintr 36])
          w (jw/make-writer (process-opts m {:max-eintr-retries 2}))
          status (jw/writer-status w)]
      (is (= :healthy (:health status)))
      (is (= 36 (:accepted-end status)))
      (is (= [{:offset 0 :remaining 36 :step :eintr}
              {:offset 0 :remaining 36 :step :eintr}
              {:offset 0 :remaining 36 :step 36}]
             ((:calls m))))))
  (testing "boundary witness: two EINTRs, partial progress, a fresh EINTR,
            completion; positive progress resets the consecutive count"
    (let [payload (ba 5 6 7 8)
          m (mock-sink [36 :eintr :eintr 2 :eintr 30])
          w (jw/make-writer (process-opts m {:max-eintr-retries 2}))
          status (jw/append! w {:payload payload})
          calls ((:calls m))]
      (is (= :healthy (:health status)))
      ;; EINTR advances zero; the 2-byte partial resumes at offset 2 after
      ;; the fresh EINTR.
      (is (= [0 0 0 2 2] (mapv :offset (rest calls))))
      (is (= [32 32 32 30 30] (mapv :remaining (rest calls))))
      (is (= 1 (:sequence status)))
      (is (= 68 (:accepted-end status)))
      (is (bytes= (jrn/encode-segment {:run-id run-id
                                       :max-payload 8
                                       :records [{:payload payload}]})
                  ((:bytes m)))))))

(deftest eintr-exhaustion-fails-absorbing-without-flush-or-sync
  (testing "allowance zero fails on the first EINTR"
    (let [m (mock-sink [36 :eintr])
          w (jw/make-writer (process-opts m {:max-eintr-retries 0}))]
      (is (= :healthy (:health (jw/writer-status w))))
      (let [status (jw/append! w {:payload (ba 1 2 3 4)})]
        (is (= :failed (:health status)))
        (is (= {:phase :record
                :reason :eintr-exhausted
                :consecutive-eintrs 1
                :max-eintr-retries 0}
               (:failure status)))
        (is (= 36 (:accepted-end status)))
        ;; Exactly one interrupted attempt: no spinning, no barrier.
        (is (= 2 (count ((:calls m)))))
        (is (= 1 ((:flushes m))))
        (is (= 0 ((:syncs m))))
        (testing "a later append is a no-I/O no-op"
          (jw/append! w {:payload (ba 9)})
          (is (= 2 (count ((:calls m)))))
          (is (= 1 ((:flushes m))))))))
  (testing "the first EINTR beyond the exact allowance fails"
    (let [m (mock-sink [36 :eintr :eintr :eintr 32])
          w (jw/make-writer (process-opts m {:max-eintr-retries 2}))
          status (jw/append! w {:payload (ba 1 2 3 4)})]
      (is (= :failed (:health status)))
      (is (= {:phase :record
              :reason :eintr-exhausted
              :consecutive-eintrs 3
              :max-eintr-retries 2}
             (:failure status)))
      (is (= 36 (:accepted-end status)))
      (is (= 4 (count ((:calls m)))))
      (is (= 1 ((:flushes m))))
      (is (= 0 ((:syncs m)))))))

;; ---- 7. fail-closed write outcomes ---------------------------------------------

(deftest invalid-error-and-throwing-writes-fail-without-spinning
  (doseq [[label step expected-reason]
          [["zero count" 0 :write-count-not-positive]
           ["negative count" -1 :write-count-not-positive]
           ["oversized count" 33 :write-count-exceeds-remaining]
           ["noninteger count" {:count "1"} :malformed-write-result]
           ["missing count" {:raw {:status :written}} :malformed-write-result]
           ["non-map result" {:raw 42} :malformed-write-result]
           ["unknown status" {:raw {:status :surprise}}
            :malformed-write-result]
           ["explicit error" {:error :enospc} :write-error]
           ["throwing write" {:throw (ex-info "boom" {})} :write-threw]]]
    (testing label
      (let [m (mock-sink [36 step])
            w (jw/make-writer (power-opts m))
            status (jw/append! w {:payload (ba 1 2 3 4)})]
        (is (= :failed (:health status)))
        (is (= :record (:phase (:failure status))))
        (is (= expected-reason (:reason (:failure status))))
        ;; accepted-end stays at the preceding boundary
        (is (= 36 (:accepted-end status)))
        (is (= 0 (:sequence status)))
        ;; exactly one record write! call: a bad result never spins
        (is (= 2 (count ((:calls m)))))
        ;; no flush/sync after the incomplete write (header barrier only)
        (is (= 1 ((:flushes m))))
        (is (= 1 ((:syncs m))))))))

(deftest write-failure-diagnostics-are-narrow
  (testing "scalar error code is retained"
    (let [m (mock-sink [36 {:error :enospc}])
          w (jw/make-writer (process-opts m))
          status (jw/append! w {:payload (ba 1)})]
      (is (= :enospc (:code (:failure status))))))
  (testing "host-object error code is represented only by its class string"
    (let [m (mock-sink [36 {:error (byte-array 2)}])
          w (jw/make-writer (process-opts m))
          failure (:failure (jw/append! w {:payload (ba 1)}))]
      (is (= :write-error (:reason failure)))
      (is (not (contains? failure :code)))
      (is (string? (:code-class failure)))
      (is (pure-data? failure))))
  (testing "throw diagnostics carry class and message strings only"
    (let [m (mock-sink [36 {:throw (ex-info "boom" {:secret (byte-array 3)})}])
          w (jw/make-writer (process-opts m))
          failure (:failure (jw/append! w {:payload (ba 1)}))]
      (is (= :write-threw (:reason failure)))
      (is (string? (:class failure)))
      (is (= "boom" (:message failure)))
      (is (not (contains? failure :secret)))
      (is (pure-data? failure))))
  (testing "malformed results carrying byte arrays are never retained"
    (let [m (mock-sink [36 {:raw {:status :written :count (byte-array 1)}}])
          w (jw/make-writer (process-opts m))
          failure (:failure (jw/append! w {:payload (ba 1)}))]
      (is (= :malformed-write-result (:reason failure)))
      (is (pure-data? failure)))))

;; ---- 8/9/10. partial frames and the durability boundary -------------------------

(deftest partial-write-then-error-leaves-physical-tail-only
  (let [payload (ba 1 2 3 4)
        m (mock-sink [36 1 {:error :io}])
        w (jw/make-writer (process-opts m))
        status (jw/append! w {:payload payload})
        bytes ((:bytes m))
        result (jrn/recover bytes)]
    (is (= :failed (:health status)))
    (is (= 36 (:accepted-end status)))
    ;; The stray partial byte stays physically in the sink...
    (is (= 37 (alength bytes)))
    ;; ...but recovery sees only the header boundary and reports the tail.
    (is (= [] (:records result)))
    (is (= 36 (:last-good-offset result)))
    (is (= :incomplete-record-header (:failure-reason result)))
    (is (bytes= (slice bytes 36 37) (:raw-tail result)))))

(deftest flush-failure-advances-accepted-not-synced-and-blocks-future-io
  (let [m (mock-sink [36 32])
        flush-count (atom 0)
        w (jw/make-writer
           (assoc (power-opts m)
                  :flush! (fn []
                            (if (= 2 (swap! flush-count inc))
                              {:status :error :code :enospc}
                              {:status :ok}))))
        s0 (jw/writer-status w)]
    (is (= 36 (:accepted-end s0)))
    (is (= 36 (:synced-end s0)))
    (let [status (jw/append! w {:payload (ba 1 2 3 4)})]
      (is (= :failed (:health status)))
      (is (= {:phase :flush :reason :flush-error :code :enospc}
             (:failure status)))
      ;; The complete frame advanced accepted-end before the barrier result.
      (is (= 68 (:accepted-end status)))
      (is (= 36 (:synced-end status)))
      (is (= 1 (:sequence status)))
      ;; The failed flush prevented the record sync (header sync only).
      (is (= 1 ((:syncs m))))
      (testing "later appends perform no I/O"
        (jw/append! w {:payload (ba 9)})
        (is (= 2 (count ((:calls m)))))
        (is (= 2 @flush-count))
        (is (= 1 ((:syncs m))))))))

(deftest sync-failure-advances-accepted-not-synced-and-preserves-first-failure
  (let [m (mock-sink [36 32])
        sync-count (atom 0)
        w (jw/make-writer
           (assoc (power-opts m)
                  :sync! (fn []
                           (if (= 2 (swap! sync-count inc))
                             {:status :error :code :efault}
                             {:status :ok}))))
        s0 (jw/writer-status w)]
    (is (= 36 (:synced-end s0)))
    (let [status (jw/append! w {:payload (ba 1 2 3 4)})
          first-failure (:failure status)]
      (is (= :failed (:health status)))
      (is (= {:phase :sync :reason :sync-error :code :efault}
             first-failure))
      (is (= 68 (:accepted-end status)))
      (is (= 36 (:synced-end status)))
      (testing "the first failure is preserved by later no-I/O appends"
        (let [again (jw/append! w {:payload (ba 9 9 9 9)})]
          (is (= first-failure (:failure again)))
          (is (= first-failure (:failure (jw/writer-status w))))
          (is (= 2 (count ((:calls m)))))
          (is (= 2 ((:flushes m))))
          (is (= 2 @sync-count)))))))

;; ---- 11. process-crash durability ------------------------------------------------

(deftest process-crash-mode-never-calls-sync
  (let [m (mock-sink [36 28 29])
        sync-called (atom 0)
        w (jw/make-writer (assoc (process-opts m)
                                 :sync! (fn []
                                          (swap! sync-called inc)
                                          {:status :ok})))]
    (jw/append! w {:payload (ba)})
    (jw/append! w {:payload (ba 1)})
    (is (= 0 @sync-called))
    (let [status (jw/writer-status w)]
      (is (= :healthy (:health status)))
      (is (= 2 (:sequence status)))
      (is (= 93 (:accepted-end status)))
      (is (= 0 (:synced-end status))))))

;; ---- 12/13. failure isolation ------------------------------------------------------

(deftest invalid-config-or-header-failure-still-returns-a-failed-writer
  (doseq [[label bad-opts]
          [["nil opts" nil]
           ["empty opts" {}]
           ["bad run-id" (assoc (good-base) :run-id (ba 1 2 3))]
           ["bad max-payload" (assoc (good-base) :max-payload -1)]
           ["max-payload above u32"
            (assoc (good-base) :max-payload 4294967296)]
           ["bad durability" (assoc (good-base) :durability :none)]
           ["negative retries" (assoc (good-base) :max-eintr-retries -1)]
           ["retries above the documented safe range"
            (assoc (good-base) :max-eintr-retries 9223372036854775807)]
           ["noninteger retries"
            (assoc (good-base) :max-eintr-retries "64")]
           ["missing write!" (dissoc (good-base) :write!)]
           ["non-fn write!" (assoc (good-base) :write! 42)]
           ["missing flush!" (dissoc (good-base) :flush!)]
           ["power without sync!"
            (assoc (dissoc (good-base) :sync!) :durability :power)]
           ["non-fn sync!" (assoc (good-base) :sync! 42)]]]
    (testing label
      (let [w (jw/make-writer bad-opts)
            status (jw/writer-status w)
            ran (atom false)]
        (is (= :failed (:health status)))
        (is (= :config (:phase (:failure status))))
        ;; The failed writer cannot prevent an application thunk.
        (is (= :app-result ((fn [] (reset! ran true) :app-result))))
        (is @ran)
        ;; Later appends are no-ops that preserve the config failure.
        (is (= (:failure status)
               (:failure (jw/append! w {:payload (ba)})))))))
  (testing "header write failure still returns a failed writer"
    (let [m (mock-sink [{:error :io}])
          w (jw/make-writer (process-opts m))
          status (jw/writer-status w)
          ran (atom false)]
      (is (= :failed (:health status)))
      (is (= {:phase :header :reason :write-error :code :io}
             (:failure status)))
      (is (= 0 (:accepted-end status)))
      (is (= 0 (:synced-end status)))
      ((fn [] (reset! ran true)))
      (is @ran)))
  (testing "throwing header write returns a failed writer, never throws"
    (let [m (mock-sink [{:throw (ex-info "hdr" {})}])
          w (jw/make-writer (process-opts m))
          status (jw/writer-status w)]
      (is (= :failed (:health status)))
      (is (= :write-threw (:reason (:failure status))))
      (is (= :header (:phase (:failure status)))))))

(deftest append-in-finally-cannot-replace-the-primary-exception
  (testing "healthy append in finally"
    (let [m (mock-sink [36 28])
          w (jw/make-writer (process-opts m))
          primary (ex-info "primary" {:id :primary})
          caught (try
                   (try
                     (throw primary)
                     (finally
                       (jw/append! w {:payload (ba)})))
                   (catch :default t
                     t))]
      (is (identical? primary caught))
      (is (= :primary (:id (ex-data caught))))
      ;; The finally append really happened.
      (is (= 1 (:sequence (jw/writer-status w))))))
  (testing "failing append in finally"
    (let [m (mock-sink [36 {:error :io}])
          w (jw/make-writer (process-opts m))
          primary (ex-info "primary" {})
          caught (try
                   (try
                     (throw primary)
                     (finally
                       (jw/append! w {:payload (ba 1)})))
                   (catch :default t
                     t))]
      (is (identical? primary caught))
      (is (= :failed (:health (jw/writer-status w)))))))

;; ---- 14. concurrent appends serialize whole transitions ----------------------------

(deftest concurrent-appends-serialize-under-the-writer-lock
  (let [chunks (atom [])
        in-flight (atom 0)
        max-in-flight (atom 0)
        write! (fn [frame offset remaining]
                 (let [n (swap! in-flight inc)]
                   (swap! max-in-flight (fn [a b] (max a b)) n))
                 ;; Bounded blocking seam: force an overlap window.
                 (Thread/sleep 25)
                 (swap! chunks conj
                        (java.util.Arrays/copyOfRange frame offset (+ offset remaining)))
                 (swap! in-flight dec)
                 {:status :written :count remaining})
        w (jw/make-writer {:run-id run-id
                           :max-payload 8
                           :durability :process-crash
                           :write! write!
                           :flush! (fn [] {:status :ok})})
        payload-a (ba 0x0a)
        payload-b (ba 0x0b)
        fa (future (jw/append! w {:kind 1 :payload payload-a}))
        fb (future (jw/append! w {:kind 2 :payload payload-b}))
        sa (deref fa 10000 :timeout-a)
        sb (deref fb 10000 :timeout-b)]
    (is (not= :timeout-a sa))
    (is (not= :timeout-b sb))
    (is (= :healthy (:health sa)))
    (is (= :healthy (:health sb)))
    (is (= 1 @max-in-flight) "underlying write calls never overlapped")
    (let [bytes (apply cat-bs (seq @chunks))
          result (jrn/recover bytes)]
      (is (nil? (:failure-reason result)))
      (is (= (alength bytes) (:last-good-offset result)))
      ;; Two contiguous records in one serialization order.
      (is (= [0 1] (mapv :sequence (:records result))))
      (is (or (and (= 1 (:kind (nth (:records result) 0)))
                   (bytes= payload-a (:payload (nth (:records result) 0)))
                   (= 2 (:kind (nth (:records result) 1)))
                   (bytes= payload-b (:payload (nth (:records result) 1))))
              (and (= 2 (:kind (nth (:records result) 0)))
                   (bytes= payload-b (:payload (nth (:records result) 0)))
                   (= 1 (:kind (nth (:records result) 1)))
                   (bytes= payload-a (:payload (nth (:records result) 1))))))
      (let [status (jw/writer-status w)]
        (is (= 2 (:sequence status)))
        (is (= (alength bytes) (:accepted-end status)))))))

;; ---- 15. payload bounds -------------------------------------------------------------

(deftest empty-and-exact-max-payloads-succeed-over-max-fails-without-io
  (let [m (mock-sink [36 28 32])
        w (jw/make-writer (process-opts m {:max-payload 4}))]
    (testing "empty payload"
      (let [status (jw/append! w {:payload (ba)})]
        (is (= :healthy (:health status)))
        (is (= 1 (:sequence status)))
        (is (= 64 (:accepted-end status)))))
    (testing "exact-max payload"
      (let [status (jw/append! w {:payload (ba 1 2 3 4)})]
        (is (= :healthy (:health status)))
        (is (= 2 (:sequence status)))
        (is (= 96 (:accepted-end status)))))
    (testing "over-max payload fails health without I/O"
      (let [status (jw/append! w {:payload (ba 1 2 3 4 5)})]
        (is (= :failed (:health status)))
        (is (= {:phase :encode
                :reason :payload-exceeds-max-payload
                :payload-length 5
                :max-payload 4}
               (:failure status)))
        (is (= 3 (count ((:calls m)))))
        ;; No flush for the rejected frame: header + two accepted records.
        (is (= 3 ((:flushes m))))
        (is (= 96 (:accepted-end status)))
        (is (= 2 (:sequence status))))))
  (testing "non-byte-array payload fails without I/O"
    (let [m (mock-sink [36])
          w (jw/make-writer (process-opts m))
          status (jw/append! w {:payload "abcd"})]
      (is (= :failed (:health status)))
      (is (= :payload-must-be-byte-array (:reason (:failure status))))
      (is (= 1 (count ((:calls m)))))))
  (testing "codec-invalid kind fails without I/O"
    (let [m (mock-sink [36])
          w (jw/make-writer (process-opts m))
          status (jw/append! w {:kind 256 :payload (ba)})]
      (is (= :failed (:health status)))
      (is (= :invalid-kind (:reason (:failure status))))
      (is (= :encode (:phase (:failure status))))
      (is (= 1 (count ((:calls m))))))))

;; ---- 16. status purity and first-failure stability -----------------------------------

(deftest writer-status-is-pure-data-with-a-stable-first-failure
  (testing "healthy status is pure data with exactly the public keys"
    (let [m (mock-sink [36 28])
          w (jw/make-writer (power-opts m))]
      (jw/append! w {:payload (ba)})
      (let [status (jw/writer-status w)]
        (is (= #{:health :failure :sequence :previous-crc32c
                 :accepted-end :synced-end :durability}
               (set (keys status))))
        (is (pure-data? status))
        (is (not (identical? status (jw/writer-status w))))
        (is (= status (jw/writer-status w))))))
  (testing "failed status is pure data and the first failure is stable"
    (let [m (mock-sink [36 {:throw (ex-info "boom" {:secret (byte-array 4)})}])
          w (jw/make-writer (process-opts m))
          first-failure (:failure (jw/append! w {:payload (ba 1 2 3 4)}))]
      (is (some? first-failure))
      (is (pure-data? (jw/writer-status w)))
      (doseq [later [(:failure (jw/append! w {:payload (ba 9)}))
                     (:failure (jw/writer-status w))
                     (:failure (jw/append! w :not-even-a-map))]]
        (is (= first-failure later)))
      ;; No further I/O after the failure.
      (is (= 2 (count ((:calls m))))))))
