(ns perturb.octet
  "perturb's byte type: unsigned octets, 0..255.

  PERTURB-DESIGN §1.5: `perturb has no JVM signed-byte[] compatibility
  obligation. A byte is an octet, 0..255. Codecs do not perform sign folding to
  read a byte.`

  WHAT IS TESTED HERE. On the current Jolt baseline a byte array reads back
  SIGNED, and worse than the design assumed: `host/chez/java/natives-array.ss`
  narrows on STORE (`na-byte-of` -> `na-u8->byte`), so `(aget (byte-array [200])
  0)` is -56 because -56 is what is in the slot, not because the accessor folded
  it. Storage is signed, not octets. §8's claim that `bytevector storage is
  correct for both` describes a future Jolt, not this one.

  So perturb cannot reach octets by choosing a different accessor over Jolt's
  byte array. It reaches them two ways, and neither touches a byte array:

    1. WIRE OCTETS come from native memory through jolt.ffi's `:uint8`, which
       lowers to Chez `foreign-ref 'unsigned-8`. That is 0..255 with no fold in
       either direction. See perturb.posix. (SHAREABLE S1.)
    2. HEAP OCTETS are a tagged persistent vector of exact integers in 0..255,
       range-checked at construction. §1.5 asks for a bytevector backing;
       Jolt exposes no bytevector to Jolt-level code, so this is the closest
       reachable representation. (INHERITED I5.)

  There is no `unchecked-byte` on any path in this namespace or any namespace
  that requires it, and no `bit-and 0xff` sign-recovery either: a value that is
  already an octet never needs one.

  CAPABILITY NOTE. §1.2 puts byte views in the capability tier. An octet view as
  built here is an ordinary immutable value, so uniqueness and linearity are
  vacuous for it; the capability discipline in this artifact is carried by the
  connection and the native buffer (perturb.cap, perturb.nrepl, perturb.posix),
  which are the things that actually have a lifetime. Recording this rather than
  claiming the tier is exercised by a vector."
  (:refer-clojure :exclude [take]))

;; --- the octet refinement ---------------------------------------------------
;; §1.3: `refinements on a byte view can state 0 <= b <= 255 as a TYPE, decidable
;; in QF-LIA`. There is no checker (out of scope), so the refinement is stated
;; twice: once as data for a future checker (perturb.cap), once as a constructor
;; obligation here.

(def refinement
  "The octet refinement, as data a future checker could consume."
  '{:perturb.octet/name  perturb.octet/octet
    :perturb.octet/sort  :int
    :perturb.octet/pred  (and (<= 0 b) (<= b 255))
    :perturb.octet/logic :QF-LIA})

(defn octet?
  "Is x an octet? Note what is NOT here: no sign test, no fold, no negative arm."
  [x]
  (and (integer? x) (<= 0 x) (<= x 255)))

;; --- the octet view ---------------------------------------------------------
;; A tagged map rather than a bare vector, so that a bencode list of small
;; integers and a bencode byte string are distinguishable values. Bencode has no
;; type tag on the wire for that distinction and perturb must not guess.

(def ^:private K :perturb.octet/octets)

;; THE RECOGNIZER IS A VALIDATOR, NOT A TAG TEST. E37 / tally row 56 recorded the
;; hole: `(and (map? x) (contains? x K))` recognised the TAG, so any hand-built
;; `{:perturb.octet/octets <anything>}` was accepted as a window while the
;; constructors above range-checked their inputs. That mattered because
;; `perturb.wire/socket`'s `:recv` declares `octets?` as its `:result-pred`, and
;; `perturb.effect/cross!` says of that predicate: "A handler that lies about its
;; result type is stopped here, not downstream in the codec." With a tag test it
;; was not stopped here; it was stopped downstream, in the codec, by a host
;; ClassCastException — or not stopped at all.
;;
;; So the recognizer now decides the same refinement the constructors enforce.
;; Three clauses, in increasing cost, and each one is load-bearing:
;;
;;   (map? x)          — O(1).
;;   (= 1 (count x))   — O(1). Every constructor here emits a map of exactly one
;;                       key, so an extra key means the value was not built here.
;;                       Rejecting it also restores VALUE EQUALITY: two windows
;;                       over the same octets that both satisfy `octets?` are
;;                       `=`, which `perturb.bencode/dget` relies on when it
;;                       looks a decoded dict key up by an `encode-utf8` of it.
;;   (vector? v)       — O(1). `osub`/`subvec`, `ovec` and `oref` all assume it.
;;   every element     — O(n), and unavoidable: the property that distinguishes a
;;                       window from a forgery is a property of the elements, so
;;                       nothing cheaper can decide it. See the cost note below.
;;
;; WHAT THIS DOES NOT DO. It does not make construction opaque, and it is not a
;; provenance check: a map this namespace never built but whose contents satisfy
;; the refinement is accepted, and is indistinguishable from — and `=` to — one
;; that was. That is the intended reading. The refinement, not the constructor's
;; signature, is the contract; §1.3 states it as a TYPE, and a type is satisfied
;; by any value with the right shape. Opacity was considered and rejected: no
;; unforgeable token is reachable from Jolt-level code (a namespace-private
;; sentinel can be read straight back out of any legitimate window), so an
;; opaque encoding would raise the bar without closing the hole, and would cost
;; a key per view on top of E36's measured ~280 B of wrapper.
;;
;; COST. `octets?` is not on the per-octet hot path — no operation E36 measured
;; calls it (`oref`, `osub`, `odrop`, `oconcat`, `ocount`, `decode-utf8` all go
;; through `ovec`, never through the recognizer). It is called once per `recv` at
;; the effect boundary and once per value in `perturb.bencode`'s encoder and
;; renderers, in both cases beside work that is already O(n) over the same
;; octets. The rejection path allocates nothing: `octets-violation` is a separate
;; function precisely so the predicate never builds a diagnostic map for the
;; integers, strings and keywords `perturb.bencode/enc` asks it about.
;;
;; WHY THE SCAN IS AN INDEXED LOOP AND NOT `every?`. `every?` is ~2.5× FASTER
;; here — ~48 against ~121 ns/octet at 8192; `-M:octetcheck` prints both — because
;; `clojure.core/every?` is `reduce`+`reduced` and drives the vector's tight
;; index loop instead of walking the trie from the root per element. It was
;; written that way first, and `dev/verify-noio.sh` rejected it: the scripted
;; run's marker window went from 0 attributable syscalls to 2, both
;; `mmap(MAP_ANONYMOUS)` — Chez growing its heap because the reduce path
;; ALLOCATES and this loop does not. Reproduced twice each way. INHERITED I11's
;; instrument is a syscall counter, so heap growth is indistinguishable from
;; I/O in it, and a recognizer on the `recv` boundary must not push the process
;; over a segment boundary. The ~2.5× is the price of an allocation-free scan and
;; it is paid once per received chunk, not once per octet read.

(defn octets?
  "Is `x` an octet view — a map of exactly the backing key, over a vector whose
  every element satisfies `octet?`?

  This DECIDES the refinement; it does not test for a tag. A forged
  `{:perturb.octet/octets [-1 999]}` is rejected here rather than becoming a
  host-level cast error somewhere inside the codec. `octets-violation` answers
  the same question with a reason attached."
  [x]
  (and (map? x)
       (= 1 (count x))
       (let [v (get x K ::absent)]
         (and (vector? v)
              (let [n (count v)]
                (loop [i 0]
                  (or (>= i n)
                      (and (octet? (nth v i))
                           (recur (inc i))))))))))

(defn octets-violation
  "nil if `x` is an octet view, else why it is not: `{:perturb.octet/reason r}`
  plus `:perturb.octet/index` and `:perturb.octet/value` when one element is at
  fault. Same decision as `octets?` — the gate checks that the two agree — but
  it allocates, so it is for diagnosis and never for dispatch."
  [x]
  (cond
    (not (map? x))
    {:perturb.octet/reason :not-a-map :perturb.octet/value x}

    (not (contains? x K))
    {:perturb.octet/reason :missing-backing-key :perturb.octet/value x}

    (not= 1 (count x))
    {:perturb.octet/reason :extra-keys
     :perturb.octet/value  (vec (remove (fn [k] (= k K)) (keys x)))}

    (not (vector? (get x K)))
    {:perturb.octet/reason :backing-not-a-vector :perturb.octet/value (get x K)}

    :else
    (let [v (get x K)
          n (count v)]
      (loop [i 0]
        (cond
          (>= i n) nil
          (octet? (nth v i)) (recur (inc i))
          :else {:perturb.octet/reason     :element-not-an-octet
                 :perturb.octet/index      i
                 :perturb.octet/value      (nth v i)
                 :perturb.octet/refinement refinement})))))

(defn ovec
  "The backing vector. Internal seam: everything else goes through oref/ocount."
  [ov] (get ov K))

(defn octets
  "Build an octet view over `xs`, checking the refinement at every element."
  [xs]
  (let [v (if (vector? xs) xs (vec xs))
        n (count v)]
    (loop [i 0]
      (if (< i n)
        (let [b (nth v i)]
          (if (octet? b)
            (recur (inc i))
            (throw (ex-info "perturb.octet: value is not an octet"
                            {:perturb.octet/index i
                             :perturb.octet/value b
                             :perturb.octet/refinement refinement}))))
        {K v}))))

(def empty-octets {K []})

(defn ocount [ov] (count (ovec ov)))

(defn oref
  "Read octet `i`. This is the whole point of the namespace: it returns 0..255
  and there is nothing between the storage read and the return value."
  [ov i]
  (nth (ovec ov) i))

(defn osub [ov from to] {K (subvec (ovec ov) from to)})

(defn odrop [ov n] (osub ov n (ocount ov)))

(defn oconcat [a b] {K (into (ovec a) (ovec b))})

(defn oseq [ov] (seq (ovec ov)))

(defn ochunks
  "Split into views of at most `n` octets. Used to prove the sans-io decoder
  really does resume from `:need-more` at an arbitrary boundary."
  [ov n]
  (let [total (ocount ov)]
    (loop [i 0 acc []]
      (if (>= i total)
        acc
        (let [j (min total (+ i n))]
          (recur j (conj acc (osub ov i j))))))))

;; --- UTF-8, written over octets ---------------------------------------------
;; Not `(.getBytes s "UTF-8")`: that returns a Jolt byte array, i.e. signed
;; storage, and recovering octets from it would be the fold this file exists to
;; avoid. Encoding from code points is both shorter and honest.

(defn- cp->octets [acc cp]
  (cond
    (< cp 0x80)    (conj acc cp)
    (< cp 0x800)   (-> acc
                       (conj (bit-or 0xC0 (bit-shift-right cp 6)))
                       (conj (bit-or 0x80 (bit-and cp 0x3F))))
    (< cp 0x10000) (-> acc
                       (conj (bit-or 0xE0 (bit-shift-right cp 12)))
                       (conj (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F)))
                       (conj (bit-or 0x80 (bit-and cp 0x3F))))
    :else          (-> acc
                       (conj (bit-or 0xF0 (bit-shift-right cp 18)))
                       (conj (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F)))
                       (conj (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F)))
                       (conj (bit-or 0x80 (bit-and cp 0x3F))))))

(defn encode-utf8
  "Host string -> octet view. Jolt strings hold Unicode scalar values (Chez
  storage, SHAREABLE S5), so `(int c)` is already a code point and no surrogate
  reassembly is needed."
  [s]
  {K (reduce cp->octets [] (map int (str s)))})

(defn- cont? [b] (= 0x80 (bit-and b 0xC0)))

(defn decode-utf8
  "Octet view -> [:ok code-points] | [:invalid reason offset].

  Returns CODE POINTS, not a host string: `clojure.core/char` refuses anything
  above 0xFFFF on this host (INHERITED I2), and that limit belongs at the
  string-construction boundary, not in the decoder. Overlong forms, surrogates
  and out-of-range scalars are rejected."
  [ov]
  (let [n (ocount ov)]
    (loop [i 0 acc []]
      (if (>= i n)
        [:ok acc]
        (let [b (oref ov i)]
          (cond
            (< b 0x80) (recur (inc i) (conj acc b))

            (< b 0xC2) [:invalid :bad-leading-octet i]

            (< b 0xE0)
            (if (or (> (+ i 2) n) (not (cont? (oref ov (inc i)))))
              [:invalid :truncated-2 i]
              (recur (+ i 2)
                     (conj acc (bit-or (bit-shift-left (bit-and b 0x1F) 6)
                                       (bit-and (oref ov (inc i)) 0x3F)))))

            (< b 0xF0)
            (if (or (> (+ i 3) n)
                    (not (cont? (oref ov (+ i 1))))
                    (not (cont? (oref ov (+ i 2)))))
              [:invalid :truncated-3 i]
              (let [cp (bit-or (bit-shift-left (bit-and b 0x0F) 12)
                               (bit-shift-left (bit-and (oref ov (+ i 1)) 0x3F) 6)
                               (bit-and (oref ov (+ i 2)) 0x3F))]
                (cond
                  (< cp 0x800) [:invalid :overlong i]
                  (and (>= cp 0xD800) (<= cp 0xDFFF)) [:invalid :surrogate i]
                  :else (recur (+ i 3) (conj acc cp)))))

            (< b 0xF5)
            (if (or (> (+ i 4) n)
                    (not (cont? (oref ov (+ i 1))))
                    (not (cont? (oref ov (+ i 2))))
                    (not (cont? (oref ov (+ i 3)))))
              [:invalid :truncated-4 i]
              (let [cp (bit-or (bit-shift-left (bit-and b 0x07) 18)
                               (bit-shift-left (bit-and (oref ov (+ i 1)) 0x3F) 12)
                               (bit-shift-left (bit-and (oref ov (+ i 2)) 0x3F) 6)
                               (bit-and (oref ov (+ i 3)) 0x3F))]
                (cond
                  (< cp 0x10000)   [:invalid :overlong i]
                  (> cp 0x10FFFF)  [:invalid :out-of-range i]
                  :else (recur (+ i 4) (conj acc cp)))))

            :else [:invalid :bad-leading-octet i]))))))

(def bmp-only-note
  "INHERITED I2, restated where it bites."
  "perturb.octet/->str cannot build a host string containing a code point above U+FFFF: jolt's clojure.core/char throws 'Value out of range for char'. The decoder is not the limit; the host string constructor is.")

(defn ->str
  "Octet view -> host string, via code points. Throws with an explicit pointer to
  INHERITED I2 on an astral code point rather than silently substituting."
  [ov]
  (let [r (decode-utf8 ov)]
    (if (= :invalid (first r))
      (throw (ex-info "perturb.octet: invalid UTF-8"
                      {:perturb.octet/reason (second r) :perturb.octet/offset (nth r 2)}))
      (apply str (map (fn [cp]
                        (if (> cp 0xFFFF)
                          (throw (ex-info bmp-only-note
                                          {:perturb.octet/code-point cp
                                           :perturb.inherited/entry :I2}))
                          (char cp)))
                      (second r))))))

(defn str-> [s] (encode-utf8 s))

;; --- rendering, for the transcript ------------------------------------------

(def ^:private hex-digits "0123456789abcdef")

(defn hex [ov]
  (apply str (interpose " " (map (fn [b]
                                   (str (nth hex-digits (bit-shift-right b 4))
                                        (nth hex-digits (bit-and b 0x0F))))
                                 (oseq ov)))))

(defn printable
  "Octets rendered one-character-per-octet with non-printables dotted. Purely for
  the transcript; nothing decodes through this."
  [ov]
  (apply str (map (fn [b] (if (and (>= b 32) (< b 127)) (char b) \.)) (oseq ov))))

;; --- the interop seam, isolated ---------------------------------------------
;; Everything above is fold-free. These two exist so the demo can EXHIBIT what
;; Jolt's byte array does with the same wire bytes. They are used by
;; perturb.demo's evidence section and by nothing else.

(defn via-jolt-byte-array
  "Round-trip an octet view through a Jolt byte array and read it back with
  `aget`. Returns the signed values. This is what perturb declines."
  [ov]
  (let [ba (byte-array (ovec ov))]
    (mapv (fn [i] (aget ba i)) (range (ocount ov)))))

(defn from-signed
  "Recover octets from signed bytes. THE FOLD. Present only so the demo can show
  the cost being declined; no perturb code path calls it."
  [signed]
  {K (mapv (fn [b] (if (< b 0) (+ b 256) b)) signed)})
