(ns jolt.sim.journal
  "Pure in-memory binary codec for the framed crash-safe worker journal
  specified in proofs/journal-wal/README.md. All integers are big endian.

  Segment header (36 bytes):
    magic[8] | format-u16 | header-length-u16 | max-payload-u32
    | run-id[16] | header-crc32c-u32

  Record (28-byte overhead):
    magic-u32 | version-u8 | kind-u8 | flags-u16 | sequence-u64
    | payload-length-u32 | previous-crc32c-u32 | payload | record-crc32c-u32

  The header CRC covers the 32 preceding header bytes. A record CRC covers
  every preceding byte of its record, including the sequence, length, and
  previous-CRC fields; the trailing record CRC is the record's completion
  marker. Sequences start at zero and increment by exactly one. The first
  record's previous-crc32c equals the validated header CRC; later records
  link to the previous validated record CRC.

  This namespace performs no file I/O, locking, buffering, flushing, EDN
  serialization, or worker wiring. Encoding produces fresh byte arrays and
  never retains caller arrays. Recovery is strictly read-only: it validates
  the complete header, scans strictly forward, stops at the first invalid or
  incomplete frame, and never repairs, resynchronizes, mutates, or aliases
  its input.

  Jolt v0.5.17 notes: byte-array aget returns signed bytes, so every read is
  normalized with (bit-and b 0xff). CRC32C state stays in 0..0xffffffff
  using the reflected Castagnoli polynomial 0x82f63b78. u64 fields are
  encoded and decoded with exact quotient/modulo arithmetic only -- no
  signed shifts, no java.util.zip.CRC32C, no ByteBuffer framing, no
  System/arraycopy, and no ordinary = on byte-array contents.")

;; ---- format constants -----------------------------------------------------

(def header-size
  "Segment header length in bytes."
  36)

(def record-overhead
  "Per-record overhead in bytes: 24-byte fixed prefix plus 4-byte trailer
  record CRC."
  28)

(def ^:no-doc max-u16 0xffff)
(def ^:no-doc max-u32 0xffffffff)
(def ^:no-doc max-u64 18446744073709551615)

;; "JSIMJRNL"
(def ^:private segment-magic-bytes
  (byte-array (map unchecked-byte
                   [0x4a 0x53 0x49 0x4d 0x4a 0x52 0x4e 0x4c])))

;; "JREC"
(def ^:private record-magic 0x4a524543)

(def ^:private format-version 1)
(def ^:private record-version 1)

;; ---- errors ---------------------------------------------------------------

(defn- fail! [reason message data]
  (throw (ex-info message
                  (assoc data
                         :type :jolt.sim.journal/invalid-argument
                         :reason reason))))

(defn- check-integer-range! [field value minimum maximum]
  (when-not (and (integer? value) (<= minimum value) (<= value maximum))
    (fail! (keyword (str "invalid-" (name field)))
           (str (name field) " must be an integer in "
                minimum ".." maximum ", got: " (pr-str value))
           {:field field :value value :minimum minimum :maximum maximum}))
  value)

;; ---- exact big-endian integer coding ---------------------------------------

(defn- get-be
  "Decodes the unsigned big-endian integer of width nbytes starting at
  (aget src off). Exact arithmetic only; the result is a bignum when an
  8-byte value exceeds the signed long range. Every signed aget byte is
  normalized with (bit-and b 0xff)."
  [src off nbytes]
  (loop [i 0
         v 0]
    (if (< i nbytes)
      (recur (inc i)
             (+ (* v 256) (bit-and (aget src (+ off i)) 0xff)))
      v)))

(defn- put-be
  "Encodes the unsigned integer v (0 <= v < 256^nbytes) big-endian into dst
  starting at (aget dst off), most significant byte first, using exact
  quotient/modulo arithmetic. No signed shifts or unchecked coercions of v
  itself; only the reduced 0..255 remainder is coerced for aset."
  [dst off nbytes v]
  (loop [i (dec nbytes)
         v v]
    (when (<= 0 i)
      (aset dst (+ off i) (unchecked-byte (mod v 256)))
      (recur (dec i) (quot v 256))))
  dst)

(defn- region=
  "True when the len bytes of a starting at aoff equal the len bytes of b
  starting at boff, compared byte by byte. Ordinary = on byte arrays is
  reference equality and is never used for contents."
  [a aoff b boff len]
  (loop [i 0]
    (if (< i len)
      (and (= (bit-and (aget a (+ aoff i)) 0xff)
              (bit-and (aget b (+ boff i)) 0xff))
           (recur (inc i)))
      true)))

;; ---- CRC32C -----------------------------------------------------------------

(defn- crc32c-byte
  "Advances the running CRC state by one normalized byte value (0..255).
  The state never leaves 0..0xffffffff: unsigned-bit-shift-right of a
  non-negative long is a logical shift, and the polynomial xor keeps bit 63
  clear."
  [crc b]
  (let [c (bit-xor crc b)]
    (loop [c c
           i 0]
      (if (< i 8)
        (recur (if (odd? c)
                 (bit-xor (unsigned-bit-shift-right c 1) 0x82f63b78)
                 (unsigned-bit-shift-right c 1))
               (inc i))
        c))))

(defn crc32c
  "CRC32C (reflected Castagnoli, polynomial 0x82f63b78) of the byte-array
  region [offset, offset+length). Returns an exact integer in
  0..0xffffffff. The two-arity form covers the whole array."
  ([bytes]
   (crc32c bytes 0 (alength bytes)))
  ([bytes offset length]
   (loop [crc 0xffffffff
          i offset
          end (+ offset length)]
     (if (< i end)
       (recur (crc32c-byte crc (bit-and (aget bytes i) 0xff))
              (inc i)
              end)
       (bit-xor crc 0xffffffff)))))

;; ---- encoding ---------------------------------------------------------------

(defn encode-header
  "Encodes a fresh 36-byte segment header.

  opts:
    :run-id      required 16-byte byte-array identifying the worker run
    :max-payload required configured maximum payload length, 0..0xffffffff

  The header CRC covers the 32 preceding header bytes. Throws ex-info with
  :type :jolt.sim.journal/invalid-argument on invalid input."
  [{:keys [run-id max-payload] :as opts}]
  (when-not (map? opts)
    (fail! :opts-must-be-map
           "encode-header opts must be a map"
           {:opts opts}))
  (when-not (and (bytes? run-id) (= 16 (alength run-id)))
    (fail! :invalid-run-id
           "run-id must be a 16-byte byte-array"
           {:run-id run-id}))
  (check-integer-range! :max-payload max-payload 0 max-u32)
  (let [header (byte-array header-size)]
    (dotimes [i 8]
      (aset header i (aget segment-magic-bytes i)))
    (put-be header 8 2 format-version)
    (put-be header 10 2 header-size)
    (put-be header 12 4 max-payload)
    (dotimes [i 16]
      (aset header (+ 16 i) (aget run-id i)))
    (put-be header 32 4 (crc32c header 0 32))
    header))

(defn encode-record
  "Encodes a fresh framed record of length 28 + (alength payload).

  opts:
    :sequence         required u64 record sequence, 0..2^64-1
    :previous-crc32c  required u32 CRC link, 0..0xffffffff
    :payload          required byte-array; copied, never retained
    :kind             record kind, 0..255, default 0
    :flags            record flags, 0..65535, default 0

  The record CRC covers every preceding byte of the record. Throws ex-info
  with :type :jolt.sim.journal/invalid-argument on invalid input."
  [{:keys [kind flags sequence previous-crc32c payload] :as opts}]
  (when-not (map? opts)
    (fail! :opts-must-be-map
           "encode-record opts must be a map"
           {:opts opts}))
  (let [kind (if (nil? kind) 0 kind)
        flags (if (nil? flags) 0 flags)]
    (check-integer-range! :sequence sequence 0 max-u64)
    (check-integer-range! :previous-crc32c previous-crc32c 0 max-u32)
    (check-integer-range! :kind kind 0 0xff)
    (check-integer-range! :flags flags 0 max-u16)
    (when-not (bytes? payload)
      (fail! :payload-must-be-byte-array
             "payload must be a byte-array"
             {:payload payload}))
    (let [length (alength payload)
          record (byte-array (+ record-overhead length))]
      (put-be record 0 4 record-magic)
      (aset record 4 (unchecked-byte record-version))
      (aset record 5 (unchecked-byte kind))
      (put-be record 6 2 flags)
      (put-be record 8 8 sequence)
      (put-be record 16 4 length)
      (put-be record 20 4 previous-crc32c)
      (dotimes [i length]
        (aset record (+ 24 i) (aget payload i)))
      (put-be record (+ 24 length) 4 (crc32c record 0 (+ 24 length)))
      record)))

(defn- concat-byte-arrays
  "Concatenates parts into one fresh byte-array using an aget/aset loop; no
  System/arraycopy."
  [parts]
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

(defn encode-segment
  "Encodes a fresh complete segment: one header plus the given records
  chained with contiguous sequences starting at zero and previous-CRC links
  starting at the validated header CRC.

  opts:
    :run-id, :max-payload as in encode-header
    :records            sequential of {:kind :flags :payload} maps, possibly
                        empty; every payload length must be <= :max-payload

  Caller payload arrays are copied, never retained. Throws ex-info with
  :type :jolt.sim.journal/invalid-argument on invalid input."
  [{:keys [run-id max-payload records] :as opts}]
  (when-not (map? opts)
    (fail! :opts-must-be-map
           "encode-segment opts must be a map"
           {:opts opts}))
  (when-not (sequential? records)
    (fail! :records-must-be-sequential
           "encode-segment :records must be a sequential collection"
           {:records records}))
  (let [header (encode-header {:run-id run-id :max-payload max-payload})
        header-crc (get-be header 32 4)
        parts (loop [rs (seq records)
                     s 0
                     prev header-crc
                     parts [header]]
                (if rs
                  (let [r (first rs)]
                    (when-not (map? r)
                      (fail! :record-must-be-map
                             "each encode-segment record must be a map"
                             {:record r :record-index s}))
                    (let [{:keys [kind flags payload]} r]
                      (when-not (bytes? payload)
                        (fail! :payload-must-be-byte-array
                               "each record :payload must be a byte-array"
                               {:record-index s :payload payload}))
                      (when (> (alength payload) max-payload)
                        (fail! :payload-exceeds-max-payload
                               "record :payload length exceeds :max-payload"
                               {:record-index s
                                :payload-length (alength payload)
                                :max-payload max-payload}))
                      (let [encoded (encode-record {:kind kind
                                                    :flags flags
                                                    :sequence s
                                                    :previous-crc32c prev
                                                    :payload payload})
                            crc (get-be encoded (+ 24 (alength payload)) 4)]
                        (recur (next rs) (inc s) crc (conj parts encoded)))))
                  parts))]
    (concat-byte-arrays parts)))

;; ---- recovery ---------------------------------------------------------------

(defn- scan-record
  "Validates one record frame at offset off without allocating or copying.

  Returns {:halt keyword} describing the first validation failure, or
  {:ok record-map :next-offset n :record-crc32c n}. Ordering follows the
  recovery contract: validate fixed bytes (magic, version, sequence,
  previous-CRC link), decode the exact unsigned u32 length, reject above the
  caller hard cap, prove by remaining-byte subtraction that payload plus
  trailer are present, and only then checksum, allocate, and copy."
  [image total off cap expected-sequence expected-previous]
  (let [remaining (- total off)]
    (cond
      (< remaining 24)
      {:halt :incomplete-record-header}

      (not= (get-be image off 4) record-magic)
      {:halt :bad-record-magic}

      (not= (bit-and (aget image (+ off 4)) 0xff) record-version)
      {:halt :unsupported-record-version}

      :else
      (let [sequence (get-be image (+ off 8) 8)
            previous (get-be image (+ off 20) 4)
            declared (get-be image (+ off 16) 4)]
        (cond
          (not= sequence expected-sequence)
          {:halt :bad-sequence}

          (not= previous expected-previous)
          {:halt :broken-previous-crc}

          ;; declared is an exact zero-extended u32 in 0..0xffffffff and cap
          ;; is at most 0xffffffff, so this comparison cannot wrap.
          (> declared cap)
          {:halt :payload-too-large}

          ;; remaining-byte subtraction proves payload presence before any
          ;; allocation: remaining >= 24 here, so the right side is >= 0.
          (> declared (- remaining 24))
          {:halt :incomplete-payload}

          ;; The full payload is present; the 4-byte trailer must also fit.
          (< (- remaining 24 declared) 4)
          {:halt :incomplete-trailer}

          :else
          (let [computed (crc32c image off (+ 24 declared))
                stored (get-be image (+ off 24 declared) 4)]
            (if (not= computed stored)
              {:halt :bad-record-crc}
              {:ok {:offset off
                    :sequence sequence
                    :kind (bit-and (aget image (+ off 5)) 0xff)
                    :flags (get-be image (+ off 6) 2)
                    :payload (java.util.Arrays/copyOfRange
                              image (+ off 24) (+ off 24 declared))
                    :previous-crc32c previous
                    :crc32c stored}
               :next-offset (+ off record-overhead declared)
               :record-crc32c stored})))))))

(defn recover
  "Strictly scans an observed segment image, read-only.

  image must be a byte-array. opts:
    :max-payload  optional caller hard cap on declared payload lengths,
                  0..0xffffffff; the effective cap is the minimum of the
                  validated header maximum and this value

  Returns:
    {:records         vector of {:offset :sequence :kind :flags :payload
                                 :previous-crc32c :crc32c} maps, in order;
                                 every :payload is a fresh byte-array
     :last-good-offset one past the last accepted record (0 when the header
                      is not accepted, 36 for a bare valid header)
     :raw-tail        fresh exact byte-array copy of image bytes
                      [last-good-offset, (alength image))
     :failure-reason  nil on a clean end exactly at the image end, else one
                      of :incomplete-header :bad-header-magic
                      :unsupported-header-format :bad-header-length
                      :bad-header-crc :incomplete-record-header
                      :bad-record-magic :unsupported-record-version
                      :bad-sequence :broken-previous-crc :payload-too-large
                      :incomplete-payload :incomplete-trailer
                      :bad-record-crc}

  The scan stops at the first invalid or incomplete frame and never repairs,
  resynchronizes, mutates, or aliases image. The bytes of image before
  :last-good-offset concatenated with :raw-tail equal image byte-for-byte.
  Throws ex-info with :type :jolt.sim.journal/invalid-argument on invalid
  arguments."
  ([image]
   (recover image {}))
  ([image opts]
   (when-not (bytes? image)
     (fail! :image-must-be-byte-array
            "recover image must be a byte-array"
            {:image image}))
   (when-not (map? opts)
     (fail! :opts-must-be-map
            "recover opts must be a map"
            {:opts opts}))
   (let [caller-cap (:max-payload opts)]
     (when (and (some? caller-cap)
                (not (and (integer? caller-cap)
                          (<= 0 caller-cap)
                          (<= caller-cap max-u32))))
       (fail! :invalid-max-payload
              "recover :max-payload must be an integer in 0..0xffffffff"
              {:max-payload caller-cap})))
   (let [total (alength image)
         result (fn [records off reason]
                  {:records records
                   :last-good-offset off
                   :raw-tail (java.util.Arrays/copyOfRange image off total)
                   :failure-reason reason})]
     (if (< total header-size)
       (result [] 0 :incomplete-header)
       (if-not (region= image 0 segment-magic-bytes 0 8)
         (result [] 0 :bad-header-magic)
         (if-not (= (get-be image 8 2) format-version)
           (result [] 0 :unsupported-header-format)
           (if-not (= (get-be image 10 2) header-size)
             (result [] 0 :bad-header-length)
             (let [header-crc (get-be image 32 4)]
               (if-not (= header-crc (crc32c image 0 32))
                 (result [] 0 :bad-header-crc)
                 (let [header-max (get-be image 12 4)
                       caller-cap (:max-payload opts)
                       cap (if (some? caller-cap)
                             (min header-max caller-cap)
                             header-max)]
                   (loop [off header-size
                          expected-sequence 0
                          expected-previous header-crc
                          records []]
                     (if (= off total)
                       (result records off nil)
                       (let [scan (scan-record image total off cap
                                               expected-sequence
                                               expected-previous)]
                         (if-let [reason (:halt scan)]
                           (result records off reason)
                           (recur (:next-offset scan)
                                  (inc expected-sequence)
                                  (:record-crc32c scan)
                                  (conj records (:ok scan)))))))))))))))))
