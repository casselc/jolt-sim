(ns jolt.sim.journal-test
  "Executable companions for the proofs/journal-wal bounded model: every SAT
  control and boundary witness converted into a test against the real pure
  encoder and strict read-only recovery scanner."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.journal :as jrn]))

;; ---- helpers ---------------------------------------------------------------

(def ^:private put-be*
  "White-box access to the exact big-endian encoder for u64 boundary tests."
  (ns-resolve 'jolt.sim.journal 'put-be))

(def ^:private get-be*
  "White-box access to the exact big-endian decoder for u64 boundary tests."
  (ns-resolve 'jolt.sim.journal 'get-be))

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

(defn- ascii [s]
  (.getBytes s "UTF-8"))

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

(defn- caught-data [f]
  (ex-data (try
             (f)
             ::no-throw
             (catch :default error
               error))))

;; ---- fixtures ----------------------------------------------------------------

(def ^:private run-id
  ;; Deliberately includes 0x80 and 0xff high bytes.
  (ba 0xde 0xad 0xbe 0xef 0x80 0xff 0x00 0x01
      0x7f 0x81 0xfe 0x02 0x03 0x04 0x05 0x06))

;; Representative multi-record segment: payload lengths 0, 5, 8 (matching the
;; proof's 0..8 payload bounds), record ends [64 97 133], total 133.
(def ^:private rep-payloads
  [(ba)
   (ba 1 2 3 4 5)
   (ba 0xff 0x80 0x00 0x7f 0xfe 0x01 0x55 0xaa)])

(def ^:private rep-max-payload 8)

(defn- rep-segment []
  (jrn/encode-segment {:run-id run-id
                       :max-payload rep-max-payload
                       :records (mapv (fn [p] {:kind 7 :flags 0 :payload p})
                                      rep-payloads)}))

(defn- record-ends
  "Cumulative end offset of each record, given the header size and payloads."
  [payloads]
  (vec (rest (reduce (fn [acc p]
                       (conj acc (+ (last acc) jrn/record-overhead (alength p))))
                     [jrn/header-size]
                     payloads))))

(defn- expected-cut-reason
  "Independent closed-form oracle for the failure reason of a crash cut:
  take record i exactly when its end offset is within the cut."
  [cut total ends payloads]
  (cond
    (< cut jrn/header-size)
    :incomplete-header

    (or (= cut total) (some #(= cut %) (cons jrn/header-size ends)))
    nil

    :else
    (let [starts (cons jrn/header-size ends)
          before (filter #(< % cut) starts)
          start (last before)
          idx (dec (count before))
          rem (- cut start)
          len (alength (nth payloads idx))]
      (cond
        (< rem 24) :incomplete-record-header
        (< (- rem 24) len) :incomplete-payload
        :else :incomplete-trailer))))

;; ---- CRC32C ------------------------------------------------------------------

(deftest crc32c-known-vectors-and-bit-corruption
  (is (= 0 (jrn/crc32c (ba))))
  (is (= 0xe3069283 (jrn/crc32c (ascii "123456789"))))
  (testing "region form matches whole-array form"
    (let [msg (ascii "123456789")
          joined (cat-bs (ba 0xaa 0xbb) msg (ba 0xcc))]
      (is (= (jrn/crc32c msg) (jrn/crc32c joined 2 (alength msg))))))
  (testing "one-bit message corruption changes the CRC"
    (let [m (ascii "123456789")]
      (aset m 4 (bit-xor (aget m 4) 0x01))
      (is (not= 0xe3069283 (jrn/crc32c m)))))
  (testing "result stays inside the unsigned 32-bit range"
    (doseq [msg [(ba) (ascii "123456789") (apply ba (range 256))]]
      (let [c (jrn/crc32c msg)]
        (is (integer? c))
        (is (<= 0 c))
        (is (<= c 0xffffffff))))))

;; ---- exact u64 coding ----------------------------------------------------------

(deftest u64-exact-boundary-roundtrip
  (testing "quotient/modulo roundtrip at and across the signed-long boundary"
    (doseq [v [0 1 255 256 65535 65536 4294967295 4294967296
               9223372036854775807
               9223372036854775808
               18446744073709551614
               18446744073709551615]]
      (let [a (byte-array 8)]
        (put-be* a 0 8 v)
        (is (= v (get-be* a 0 8)) (str "u64 roundtrip " v)))))
  (testing "exact wire byte patterns at the boundaries"
    (let [a (byte-array 8)]
      (put-be* a 0 8 9223372036854775807)
      (is (bytes= (ba 0x7f 0xff 0xff 0xff 0xff 0xff 0xff 0xff) a))
      (put-be* a 0 8 9223372036854775808)
      (is (bytes= (ba 0x80 0x00 0x00 0x00 0x00 0x00 0x00 0x00) a))
      (put-be* a 0 8 18446744073709551615)
      (is (bytes= (ba 0xff 0xff 0xff 0xff 0xff 0xff 0xff 0xff) a))))
  (testing "public encode-record writes the same boundary byte patterns"
    (doseq [[v expected]
            [[9223372036854775807 (ba 0x7f 0xff 0xff 0xff 0xff 0xff 0xff 0xff)]
             [9223372036854775808 (ba 0x80 0x00 0x00 0x00 0x00 0x00 0x00 0x00)]
             [18446744073709551615 (ba 0xff 0xff 0xff 0xff 0xff 0xff 0xff 0xff)]]]
      (let [record (jrn/encode-record {:sequence v
                                       :previous-crc32c 0
                                       :payload (ba)})]
        (is (bytes= expected (slice record 8 16)) (str "sequence " v))
        (is (= v (get-be* record 8 8)) (str "sequence decode " v))))))

(deftest fixed-fields-have-independent-big-endian-wire-layout
  (testing "segment header prefix"
    (let [id (apply ba (range 16))
          header (jrn/encode-header {:run-id id :max-payload 0x01020304})
          expected-prefix
          (cat-bs (ascii "JSIMJRNL")
                  (ba 0x00 0x01
                      0x00 0x24
                      0x01 0x02 0x03 0x04)
                  id)]
      (is (= 32 (alength expected-prefix)))
      (is (bytes= expected-prefix (slice header 0 32)))
      (is (= (jrn/crc32c expected-prefix) (get-u32 header 32)))))
  (testing "record prefix and payload"
    (let [payload (ba 0x80 0xff)
          record (jrn/encode-record
                  {:kind 0xab
                   :flags 0xcdef
                   :sequence 0x0102030405060708
                   :previous-crc32c 0xa1b2c3d4
                   :payload payload})
          expected-prefix
          (ba 0x4a 0x52 0x45 0x43
              0x01 0xab 0xcd 0xef
              0x01 0x02 0x03 0x04 0x05 0x06 0x07 0x08
              0x00 0x00 0x00 0x02
              0xa1 0xb2 0xc3 0xd4
              0x80 0xff)]
      (is (= 26 (alength expected-prefix)))
      (is (bytes= expected-prefix (slice record 0 26)))
      (is (= (jrn/crc32c expected-prefix) (get-u32 record 26))))))

;; ---- argument validation -------------------------------------------------------

(deftest encoder-and-recovery-validate-arguments
  (testing "run-id must be a 16-byte byte-array"
    (doseq [bad [(ba 1) (apply ba (range 15)) (apply ba (range 17))
                 "0123456789abcdef" nil]]
      (is (= :invalid-run-id
             (:reason (caught-data
                       #(jrn/encode-header {:run-id bad :max-payload 8}))))
          (pr-str bad))))
  (testing "max-payload range"
    (doseq [bad [-1 4294967296 "8" nil]]
      (is (= :invalid-max-payload
             (:reason (caught-data
                       #(jrn/encode-header {:run-id run-id :max-payload bad}))))
          (pr-str bad))))
  (testing "record numeric ranges"
    (doseq [[opts reason]
            [[{:sequence -1 :previous-crc32c 0 :payload (ba)} :invalid-sequence]
             [{:sequence 18446744073709551616 :previous-crc32c 0 :payload (ba)}
              :invalid-sequence]
             [{:previous-crc32c 0 :payload (ba)} :invalid-sequence]
             [{:sequence 0 :previous-crc32c -1 :payload (ba)}
              :invalid-previous-crc32c]
             [{:sequence 0 :previous-crc32c 4294967296 :payload (ba)}
              :invalid-previous-crc32c]
             [{:sequence 0 :previous-crc32c 0 :kind 256 :payload (ba)}
              :invalid-kind]
             [{:sequence 0 :previous-crc32c 0 :kind -1 :payload (ba)}
              :invalid-kind]
             [{:sequence 0 :previous-crc32c 0 :kind false :payload (ba)}
              :invalid-kind]
             [{:sequence 0 :previous-crc32c 0 :flags 65536 :payload (ba)}
              :invalid-flags]
             [{:sequence 0 :previous-crc32c 0 :flags -1 :payload (ba)}
              :invalid-flags]
             [{:sequence 0 :previous-crc32c 0 :flags false :payload (ba)}
              :invalid-flags]]]
      (is (= reason (:reason (caught-data #(jrn/encode-record opts))))
          (pr-str opts))))
  (testing "payload type"
    (doseq [bad [nil [1 2 3] "abc"]]
      (is (= :payload-must-be-byte-array
             (:reason (caught-data
                       #(jrn/encode-record {:sequence 0 :previous-crc32c 0
                                            :payload bad}))))
          (pr-str bad))))
  (testing "segment record collection and per-record limits"
    (is (= :records-must-be-sequential
           (:reason (caught-data
                     #(jrn/encode-segment {:run-id run-id :max-payload 8
                                           :records 42})))))
    (is (= :record-must-be-map
           (:reason (caught-data
                     #(jrn/encode-segment {:run-id run-id :max-payload 8
                                           :records [:not-a-map]})))))
    (is (= :payload-must-be-byte-array
           (:reason (caught-data
                     #(jrn/encode-segment {:run-id run-id :max-payload 8
                                           :records [{:payload "abc"}]})))))
    (is (= :payload-exceeds-max-payload
           (:reason (caught-data
                     #(jrn/encode-segment {:run-id run-id :max-payload 8
                                           :records [{:payload (apply ba (range 9))}]}))))))
  (testing "recover argument validation"
    (doseq [bad [nil [1 2 3] "abc"]]
      (is (= :image-must-be-byte-array
             (:reason (caught-data #(jrn/recover bad)))) (pr-str bad)))
    (doseq [bad [-1 4294967296 "8"]]
      (is (= :invalid-max-payload
             (:reason (caught-data #(jrn/recover (ba) {:max-payload bad}))))
          (pr-str bad))))
  (testing "validation errors carry the stable type"
    (is (= :jolt.sim.journal/invalid-argument
           (:type (caught-data #(jrn/encode-header {:run-id (ba) :max-payload 8})))))))

;; ---- empty and exact-boundary segments -----------------------------------------

(deftest empty-and-exact-boundary-segments
  (testing "empty image"
    (let [result (jrn/recover (ba))]
      (is (= [] (:records result)))
      (is (= 0 (:last-good-offset result)))
      (is (= :incomplete-header (:failure-reason result)))
      (is (bytes? (:raw-tail result)))
      (is (zero? (alength (:raw-tail result))))))
  (testing "35-byte truncated header"
    (let [segment (rep-segment)
          result (jrn/recover (slice segment 0 35))]
      (is (= 0 (:last-good-offset result)))
      (is (= :incomplete-header (:failure-reason result)))
      (is (= 35 (alength (:raw-tail result))))))
  (testing "zero records: bare valid header is a clean segment"
    (let [segment (jrn/encode-segment {:run-id run-id :max-payload 8 :records []})
          result (jrn/recover segment)]
      (is (= jrn/header-size (alength segment)))
      (is (= [] (:records result)))
      (is (= jrn/header-size (:last-good-offset result)))
      (is (nil? (:failure-reason result)))
      (is (zero? (alength (:raw-tail result))))))
  (testing "zero-length payload record"
    (let [segment (jrn/encode-segment {:run-id run-id :max-payload 8
                                       :records [{:kind 3 :payload (ba)}]})
          result (jrn/recover segment)]
      (is (nil? (:failure-reason result)))
      (is (= 1 (count (:records result))))
      (is (zero? (alength (:payload (first (:records result)))))))
    (testing "max-payload zero still admits empty payloads"
      (let [segment (jrn/encode-segment {:run-id run-id :max-payload 0
                                         :records [{:payload (ba)}]})
            result (jrn/recover segment)]
        (is (nil? (:failure-reason result)))
        (is (= 1 (count (:records result)))))))
  (testing "exact maximum payload"
    (let [payload (apply ba (range rep-max-payload))
          segment (jrn/encode-segment {:run-id run-id
                                       :max-payload rep-max-payload
                                       :records [{:kind 9 :payload payload}]})
          result (jrn/recover segment)]
      (is (nil? (:failure-reason result)))
      (is (= 1 (count (:records result))))
      (is (bytes= payload (:payload (first (:records result)))))
      (is (= (alength segment) (:last-good-offset result))))))

;; ---- crash cuts -----------------------------------------------------------------

(deftest crash-cut-every-byte-recovers-exact-maximal-prefix
  (let [segment (rep-segment)
        total (alength segment)
        ends (record-ends rep-payloads)]
    (is (= [64 97 133] ends))
    (doseq [cut (range (inc total))]
      (let [image (slice segment 0 cut)
            result (jrn/recover image)
            boundaries (cons jrn/header-size ends)
            good (filterv #(<= % cut) boundaries)
            expected-count (if (seq good) (dec (count good)) 0)
            expected-last-good (if (seq good) (last good) 0)
            expected-reason (expected-cut-reason cut total ends rep-payloads)]
        (is (= expected-count (count (:records result))) (str "cut " cut))
        (is (= expected-last-good (:last-good-offset result)) (str "cut " cut))
        (is (= expected-reason (:failure-reason result)) (str "cut " cut))
        ;; Recovery accepts exactly the maximal complete initial prefix.
        (is (= (range expected-count)
               (mapv :sequence (:records result)))
            (str "cut " cut))
        (doseq [i (range expected-count)]
          (is (bytes= (nth rep-payloads i)
                      (:payload (nth (:records result) i)))
              (str "cut " cut " record " i)))
        ;; Bytes before last-good-offset plus raw-tail equal the observed
        ;; image byte-for-byte.
        (is (bytes= (slice segment expected-last-good cut) (:raw-tail result))
            (str "raw-tail cut " cut))
        (is (bytes= image
                    (cat-bs (slice image 0 (:last-good-offset result))
                            (:raw-tail result)))
            (str "reconstruction cut " cut))
        ;; The scanner never mutates its input.
        (is (bytes= (slice segment 0 cut) image)
            (str "input preserved cut " cut))))))

(deftest cut-immediately-before-trailing-crc
  (let [payload (apply ba (range 8))
        segment (jrn/encode-segment {:run-id run-id
                                     :max-payload 8
                                     :records [{:kind 1 :payload payload}]})
        total (alength segment)]
    (is (= (+ jrn/header-size jrn/record-overhead 8) total))
    (testing "trailer entirely absent"
      (let [result (jrn/recover (slice segment 0 (- total 4)))]
        (is (= [] (:records result)))
        (is (= jrn/header-size (:last-good-offset result)))
        (is (= :incomplete-trailer (:failure-reason result)))))
    (testing "one to three trailer bytes present"
      (doseq [present [1 2 3]]
        (let [result (jrn/recover (slice segment 0 (+ (- total 4) present)))]
          (is (= [] (:records result)))
          (is (= :incomplete-trailer (:failure-reason result))))))
    (testing "complete trailer recovers the record"
      (let [result (jrn/recover segment)]
        (is (nil? (:failure-reason result)))
        (is (= 1 (count (:records result))))))))

;; ---- header validation ------------------------------------------------------------

(deftest corrupt-complete-header-cannot-admit-a-record
  (let [base (rep-segment)
        corrupt (fn [f]
                  (let [image (aclone base)]
                    (f image)
                    image))
        variants
        [["bad magic byte"
          (fn [image] (aset image 0 (bit-xor (aget image 0) 0x01)))
          :bad-header-magic]
         ["unsupported format version"
          (fn [image] (aset image 8 (unchecked-byte 0))
                (aset image 9 (unchecked-byte 2)))
          :unsupported-header-format]
         ["wrong header length"
          (fn [image] (aset image 10 (unchecked-byte 0))
                (aset image 11 (unchecked-byte 35)))
          :bad-header-length]
         ["bad header CRC field"
          (fn [image] (aset image 32 (bit-xor (aget image 32) 0x01)))
          :bad-header-crc]
         ["corrupted CRC-covered max-payload byte"
          (fn [image] (aset image 12 (bit-xor (aget image 12) 0x10)))
          :bad-header-crc]
         ["corrupted CRC-covered run-id byte"
          (fn [image] (aset image 20 (bit-xor (aget image 20) 0x40)))
          :bad-header-crc]]]
    (doseq [[label f expected] variants]
      (let [image (corrupt f)
            result (jrn/recover image)]
        (is (= [] (:records result)) label)
        (is (= 0 (:last-good-offset result)) label)
        (is (= expected (:failure-reason result)) label)
        (is (bytes= image (:raw-tail result)) label)))))

;; ---- chain corruption without resynchronization ------------------------------------

(deftest corrupt-record-halts-scan-without-resync
  (let [segment (rep-segment)
        ends (record-ends rep-payloads)
        corrupt-at (fn [off]
                     (let [image (aclone segment)]
                       (aset image off (bit-xor (aget image off) 0x01))
                       image))]
    (testing "corrupt first record admits nothing"
      (let [;; First record has an empty payload; corrupt its CRC-covered
            ;; kind byte instead.
            image (corrupt-at (+ jrn/header-size 5))
            result (jrn/recover image)]
        (is (= [] (:records result)))
        (is (= jrn/header-size (:last-good-offset result)))
        (is (= :bad-record-crc (:failure-reason result)))))
    (testing "corrupt middle record admits only the prefix, no resync"
      (let [;; Middle record payload starts at 64 + 24 = 88; the third record
            ;; at 97 remains fully valid-looking behind it.
            image (corrupt-at 88)
            result (jrn/recover image)]
        (is (= 1 (count (:records result))))
        (is (= 0 (:sequence (first (:records result)))))
        (is (= (nth ends 0) (:last-good-offset result)))
        (is (= :bad-record-crc (:failure-reason result)))
        (is (bytes= (slice image (nth ends 0) (alength image))
                    (:raw-tail result)))))
    (testing "corrupt last record admits the two earlier records"
      (let [image (corrupt-at (+ (nth ends 1) 24))
            result (jrn/recover image)]
        (is (= 2 (count (:records result))))
        (is (= (nth ends 1) (:last-good-offset result)))
        (is (= :bad-record-crc (:failure-reason result)))))))

(deftest sequence-reorder-delete-and-previous-crc-failures
  (let [header (jrn/encode-header {:run-id run-id :max-payload 64})
        header-crc (get-u32 header 32)
        r0 (jrn/encode-record {:kind 1 :sequence 0
                               :previous-crc32c header-crc
                               :payload (ascii "zero")})
        r0-crc (get-u32 r0 (+ 24 4))
        r1 (jrn/encode-record {:kind 2 :sequence 1
                               :previous-crc32c r0-crc
                               :payload (ascii "one")})
        r1-crc (get-u32 r1 (+ 24 3))
        r2 (jrn/encode-record {:kind 3 :sequence 2
                               :previous-crc32c r1-crc
                               :payload (ascii "two")})]
    (testing "sequence gap with otherwise valid CRC"
      (let [gap (jrn/encode-record {:kind 3 :sequence 2
                                    :previous-crc32c r0-crc
                                    :payload (ascii "two")})
            result (jrn/recover (cat-bs header r0 gap))]
        (is (= 1 (count (:records result))))
        (is (= :bad-sequence (:failure-reason result)))
        (is (= (+ jrn/header-size (alength r0)) (:last-good-offset result)))))
    (testing "reordered middle record, all bytes individually valid"
      (let [result (jrn/recover (cat-bs header r0 r2 r1))]
        (is (= 1 (count (:records result))))
        (is (= :bad-sequence (:failure-reason result)))))
    (testing "deleted middle record"
      (let [result (jrn/recover (cat-bs header r0 r2))]
        (is (= 1 (count (:records result))))
        (is (= :bad-sequence (:failure-reason result)))))
    (testing "broken first previous-CRC link"
      (let [bad (jrn/encode-record {:kind 1 :sequence 0
                                    :previous-crc32c (bit-xor header-crc 1)
                                    :payload (ascii "zero")})
            result (jrn/recover (cat-bs header bad))]
        (is (= [] (:records result)))
        (is (= :broken-previous-crc (:failure-reason result)))))
    (testing "broken later previous-CRC link"
      (let [bad (jrn/encode-record {:kind 2 :sequence 1
                                    :previous-crc32c (bit-xor r0-crc 1)
                                    :payload (ascii "one")})
            result (jrn/recover (cat-bs header r0 bad))]
        (is (= 1 (count (:records result))))
        (is (= :broken-previous-crc (:failure-reason result)))))
    (testing "bad record magic"
      (let [bad (aclone r0)]
        (aset bad 0 (bit-xor (aget bad 0) 0x01))
        (let [result (jrn/recover (cat-bs header bad))]
          (is (= [] (:records result)))
          (is (= :bad-record-magic (:failure-reason result))))))
    (testing "unsupported record version"
      (let [bad (aclone r0)]
        (aset bad 4 (unchecked-byte 2))
        (let [result (jrn/recover (cat-bs header bad))]
          (is (= [] (:records result)))
          (is (= :unsupported-record-version (:failure-reason result))))))))

;; ---- declared length safety ---------------------------------------------------------

(defn- prefix-with-length
  "A 24-byte record fixed prefix (sequence 0, correct previous-CRC link)
  carrying an attacker-chosen declared payload length. No allocation or
  checksum may happen before the length and availability checks reject it."
  [declared previous-crc]
  (let [record (jrn/encode-record {:sequence 0
                                   :previous-crc32c previous-crc
                                   :payload (ba)})]
    (put-be* record 16 4 declared)
    (slice record 0 24)))

(deftest declared-length-never-drives-early-allocation
  (testing "0xffffffff declared length, maximal header maximum"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 0xffffffff})
          header-crc (get-u32 header 32)
          image (cat-bs header (prefix-with-length 0xffffffff header-crc))
          result (jrn/recover image)]
      (is (= [] (:records result)))
      (is (= :incomplete-payload (:failure-reason result)))
      (is (= jrn/header-size (:last-good-offset result)))
      (is (bytes= image (cat-bs (slice image 0 (:last-good-offset result))
                                (:raw-tail result))))))
  (testing "0xffffffff declared length, caller hard cap"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 0xffffffff})
          header-crc (get-u32 header 32)
          image (cat-bs header (prefix-with-length 0xffffffff header-crc))]
      (let [result (jrn/recover image {:max-payload 65536})]
        (is (= :payload-too-large (:failure-reason result))))
      (let [result (jrn/recover image {:max-payload 0})]
        (is (= :payload-too-large (:failure-reason result))))))
  (testing "one above the configured maximum"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 64})
          header-crc (get-u32 header 32)
          filler (byte-array 128)
          image (cat-bs header (prefix-with-length 65 header-crc) filler)
          result (jrn/recover image)]
      (is (= [] (:records result)))
      (is (= :payload-too-large (:failure-reason result)))))
  (testing "in-range declared length with absent payload"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 64})
          header-crc (get-u32 header 32)
          image (cat-bs header (prefix-with-length 64 header-crc))
          result (jrn/recover image)]
      (is (= [] (:records result)))
      (is (= :incomplete-payload (:failure-reason result)))))
  (testing "in-range declared length with short payload"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 64})
          header-crc (get-u32 header 32)
          image (cat-bs header (prefix-with-length 64 header-crc)
                        (byte-array 63))
          result (jrn/recover image)]
      (is (= :incomplete-payload (:failure-reason result)))))
  (testing "full payload with absent or short trailer"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 64})
          header-crc (get-u32 header 32)
          prefix (prefix-with-length 64 header-crc)]
      (let [result (jrn/recover (cat-bs header prefix (byte-array 64)))]
        (is (= :incomplete-trailer (:failure-reason result))))
      (let [result (jrn/recover (cat-bs header prefix (byte-array 64)
                                        (ba 0 0 0)))]
        (is (= :incomplete-trailer (:failure-reason result))))))
  (testing "incomplete record fixed header"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 64})
          image (cat-bs header (ba 0x4a 0x52 0x45))
          result (jrn/recover image)]
      (is (= [] (:records result)))
      (is (= :incomplete-record-header (:failure-reason result))))))

;; ---- high bytes ----------------------------------------------------------------------

(deftest high-bytes-roundtrip-exactly
  (testing "encode-header preserves high run-id bytes"
    (let [header (jrn/encode-header {:run-id run-id :max-payload 256})]
      (is (bytes= run-id (slice header 16 32)))))
  (testing "payload, kind, and flags carrying 0x80/0xff survive the roundtrip"
    (let [payload (apply ba (range 256))
          segment (jrn/encode-segment {:run-id run-id
                                       :max-payload 256
                                       :records [{:kind 0xff
                                                  :flags 0xffff
                                                  :payload payload}]})
          result (jrn/recover segment)]
      (is (nil? (:failure-reason result)))
      (is (= 1 (count (:records result))))
      (let [record (first (:records result))]
        (is (= 0xff (:kind record)))
        (is (= 0xffff (:flags record)))
        (is (= 0 (:sequence record)))
        (is (bytes= payload (:payload record)))))))

;; ---- raw tail --------------------------------------------------------------------------

(deftest raw-tail-is-exact-fresh-and-never-aliases-input
  (let [segment (rep-segment)
        ends (record-ends rep-payloads)
        image (aclone segment)]
    ;; Corrupt the middle record payload (offset 88).
    (aset image 88 (bit-xor (aget image 88) 0x01))
    (let [result (jrn/recover image)
          tail (:raw-tail result)]
      (is (= (nth ends 0) (:last-good-offset result)))
      (is (bytes= (slice image (nth ends 0) (alength image)) tail))
      (testing "mutating the returned tail never touches the input"
        (let [before (aclone image)]
          (dotimes [i (alength tail)]
            (aset tail i (bit-xor (aget tail i) 0xff)))
          (is (bytes= before image)))))
    (testing "clean end yields an empty but real byte-array tail"
      (let [result (jrn/recover segment)]
        (is (nil? (:failure-reason result)))
        (is (bytes? (:raw-tail result)))
        (is (zero? (alength (:raw-tail result))))))))

;; ---- determinism and mutation isolation -------------------------------------------------

(deftest repeated-recovery-is-deterministic-with-fresh-arrays
  (let [segment (rep-segment)
        before (aclone segment)
        r1 (jrn/recover segment)
        r2 (jrn/recover segment)]
    (is (= (:failure-reason r1) (:failure-reason r2)))
    (is (= (:last-good-offset r1) (:last-good-offset r2)))
    (is (= (count (:records r1)) (count (:records r2))))
    (doseq [[a b] (map vector (:records r1) (:records r2))]
      (is (= (dissoc a :payload) (dissoc b :payload)))
      (is (bytes= (:payload a) (:payload b)))
      (is (not (identical? (:payload a) (:payload b)))))
    (is (not (identical? (:raw-tail r1) (:raw-tail r2))))
    (testing "mutating one result's arrays affects neither the input nor
              another recovery of the same bytes"
      (doseq [record (:records r1)]
        (let [p (:payload record)]
          (when (pos? (alength p))
            (aset p 0 (bit-xor (aget p 0) 0xff)))))
      (when (pos? (alength (:raw-tail r1)))
        (aset (:raw-tail r1) 0 (bit-xor (aget (:raw-tail r1) 0) 0xff)))
      (is (bytes= before segment))
      (let [r3 (jrn/recover segment)]
        (doseq [[b c] (map vector (:records r2) (:records r3))]
          (is (bytes= (:payload b) (:payload c)))))
      (doseq [[expected record] (map vector rep-payloads (:records r2))]
        (is (bytes= expected (:payload record)))))))

(deftest encoder-never-retains-caller-arrays
  (testing "encode-record copies the caller payload"
    (let [payload (ba 1 2 3)
          original (aclone payload)
          record (jrn/encode-record {:sequence 0
                                     :previous-crc32c 0
                                     :payload payload})]
      (aset payload 0 (unchecked-byte 0xff))
      (is (bytes= original (slice record 24 27)))))
  (testing "encode-segment copies caller payloads and run-id"
    (let [payload (ba 4 5 6)
          original (aclone payload)
          id (aclone run-id)
          segment (jrn/encode-segment {:run-id id
                                       :max-payload 8
                                       :records [{:payload payload}]})]
      (aset payload 0 (unchecked-byte 0xff))
      (aset id 0 (unchecked-byte 0x00))
      (let [result (jrn/recover segment)]
        (is (nil? (:failure-reason result)))
        (is (bytes= original (:payload (first (:records result)))))
        (is (bytes= run-id (slice segment 16 32))))))
  (testing "recovered payloads never alias the scanned image"
    (let [segment (rep-segment)
          result (jrn/recover segment)
          payloads (mapv :payload (:records result))]
      (dotimes [i (alength segment)]
        (aset segment i (unchecked-byte 0)))
      (doseq [[expected p] (map vector rep-payloads payloads)]
        (is (bytes= expected p))))))
