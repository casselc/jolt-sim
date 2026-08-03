(ns perturb.bencode
  "bencode over unsigned octets. Sans-io: pure step functions, no blocking, no
  I/O, no continuations.

  SHAPE. E4 measured the trichotomy that `jolt.bytes/read-window` and
  `jolt.bencode/decode` already use — `:ok` with a new cursor, `:need-more` with
  the EXACT ORIGINAL cursor, `:invalid` with reason/offset and the original
  cursor, commit only at the exact frame boundary — over 132,672 assertions. It
  is adopted here because it was measured, not because it is Jolt's (§7's
  evidence/convention/specification split: this is evidence).

  E13 typed the driver against exactly this contract and found the check does
  real work: weakening `decode` to permit a foreign result window, or dropping
  the upper bound on the result position, makes the driver's own obligation
  unprovable. Those refinements are written as data in `contract` below, for the
  checker that does not exist.

  BYTES ARE BYTES. A bencode string is a byte string. perturb decodes it to an
  octet view, not to text — text is a decision the protocol layer makes, at the
  field it knows to be text. `jolt.nrepl` decodes to a latin1 host string
  instead, which is a lossy re-encoding of the same octets and is exactly the
  interop convention §1.6 says belongs in `clojure.*`.

  NO FOLD. There is no `unchecked-byte`, no `bit-and 0xff`, and no signed value
  anywhere in this file. Every comparison against a delimiter compares an octet
  to an octet."
  (:require [perturb.octet :as o]))

;; --- delimiters, as octets --------------------------------------------------
(def ^:private I  0x69)   ; \i
(def ^:private L  0x6C)   ; \l
(def ^:private D  0x64)   ; \d
(def ^:private E  0x65)   ; \e
(def ^:private CN 0x3A)   ; \:
(def ^:private MI 0x2D)   ; \-
(def ^:private ZERO 0x30)
(def ^:private NINE 0x39)

(defn- digit? [b] (and (>= b ZERO) (<= b NINE)))

;; --- the contract, as data --------------------------------------------------

(def contract
  "E13's declared refinement for `decode`, written where a checker could read it.
  Hand-written and unchecked. Written in a s-expression refinement syntax
  (`refine` binds the result name and states its predicate) rather than the
  set-builder notation E13's prototypes use, because jolt's reader rejects a bare
  `:` and a bare `|` — an inherited reader limitation, noted rather than fought."
  '{:perturb.bencode/step decode
    :perturb.bencode/args
    [{:name ov  :sort Octets}
     {:name pos :sort Int :refine (and (<= 0 pos) (<= pos (ocount ov)))}]
    :perturb.bencode/result
    (one-of
      ;; commit only at an exact frame boundary, and strictly forward
      {:tag :ok        :refine (and (< pos pos-out) (<= pos-out (ocount ov)))
                       :view   (identical-backing ov)}
      ;; the EXACT original cursor, per E4
      {:tag :need-more :refine (= pos-out pos)}
      {:tag :invalid   :refine (and (= pos-out pos)
                                    (<= pos off) (<= off (ocount ov)))})
    :perturb.bencode/notes
    "E13: weakening identical-backing to permit a foreign backing, or dropping the
     upper bound on pos-out, makes the driver's WF_CURSOR obligation unprovable."})

;; --- encode -----------------------------------------------------------------
;; Produces an octet vector directly. Nothing here builds a string first.

(declare enc)

(defn- enc-int [acc n]
  (into (conj acc I) (conj (o/ovec (o/encode-utf8 (str n))) E)))

(defn- enc-bytes [acc v]
  ;; v is a plain octet vector
  (-> acc
      (into (o/ovec (o/encode-utf8 (str (count v)))))
      (conj CN)
      (into v)))

(defn- key-name [k]
  (cond (keyword? k) (name k)
        (symbol? k)  (name k)
        (string? k)  k
        (o/octets? k) (o/->str k)
        :else (str k)))

(defn- enc [acc v]
  (cond
    (o/octets? v) (enc-bytes acc (o/ovec v))
    (integer? v)  (enc-int acc v)
    (string? v)   (enc-bytes acc (o/ovec (o/encode-utf8 v)))
    (keyword? v)  (enc-bytes acc (o/ovec (o/encode-utf8 (name v))))
    (symbol? v)   (enc-bytes acc (o/ovec (o/encode-utf8 (name v))))
    ;; INHERITED I13: nil -> empty byte string is jolt.nrepl's convention, not
    ;; bencode's, and it is lossy. Reproduced so the differential oracle overlaps.
    (nil? v)      (enc-bytes acc [])
    (map? v)      (let [;; INHERITED I8: ordered by key NAME, matching
                        ;; jolt.nrepl; bencode says raw byte string.
                        pairs (sort-by (fn [p] (key-name (first p))) (seq v))]
                    (conj (reduce (fn [a p]
                                    (enc (enc-bytes a (o/ovec (o/encode-utf8 (key-name (first p)))))
                                         (second p)))
                                  (conj acc D) pairs)
                          E))
    (or (vector? v) (seq? v) (list? v))
    (conj (reduce enc (conj acc L) v) E)

    :else (throw (ex-info "perturb.bencode: not encodable"
                          {:perturb.bencode/value v}))))

(defn encode
  "Value -> octet view."
  [v] (o/octets (enc [] v)))

;; --- decode -----------------------------------------------------------------
;; [:ok value pos'] | [:need-more pos] | [:invalid reason offset pos]

(declare decode)

(defn- scan-int
  "Read digits (with optional leading -) from `pos` until `terminator`.
  -> [:ok n after] | [:need-more] | [:invalid reason off]"
  [ov pos terminator]
  (let [n (o/ocount ov)]
    (loop [i pos neg false seen false acc 0]
      (if (>= i n)
        [:need-more]
        (let [b (o/oref ov i)]
          (cond
            (= b terminator) (if seen
                               [:ok (if neg (- acc) acc) (inc i)]
                               [:invalid :empty-integer i])
            (and (= b MI) (= i pos) (not neg)) (recur (inc i) true seen acc)
            (digit? b) (recur (inc i) neg true (+ (* acc 10) (- b ZERO)))
            :else [:invalid :not-a-digit i]))))))

(defn- decode-bytes [ov pos]
  (let [n (o/ocount ov)
        r (scan-int ov pos CN)]
    (cond
      (= :need-more (first r)) [:need-more pos]
      (= :invalid (first r))   [:invalid (second r) (nth r 2) pos]
      :else
      (let [len   (second r)
            start (nth r 2)
            end   (+ start len)]
        (cond
          (neg? len)   [:invalid :negative-length pos pos]
          (> end n)    [:need-more pos]
          :else        [:ok (o/osub ov start end) end])))))

(defn- decode-list [ov pos]
  (let [n (o/ocount ov)]
    (loop [i (inc pos) acc []]
      (if (>= i n)
        [:need-more pos]
        (if (= E (o/oref ov i))
          [:ok acc (inc i)]
          (let [r (decode ov i)]
            (cond
              (= :ok (first r))        (recur (nth r 2) (conj acc (second r)))
              (= :need-more (first r)) [:need-more pos]     ; exact original cursor
              :else                    [:invalid (second r) (nth r 2) pos])))))))

(defn- decode-dict [ov pos]
  (let [n (o/ocount ov)]
    (loop [i (inc pos) acc {}]
      (if (>= i n)
        [:need-more pos]
        (if (= E (o/oref ov i))
          [:ok acc (inc i)]
          (let [kr (decode-bytes ov i)]
            (cond
              (= :need-more (first kr)) [:need-more pos]
              (= :invalid (first kr))   [:invalid (second kr) (nth kr 2) pos]
              :else
              (let [k  (second kr)
                    vr (decode ov (nth kr 2))]
                (cond
                  (= :ok (first vr))        (recur (nth vr 2) (assoc acc k (second vr)))
                  (= :need-more (first vr)) [:need-more pos]
                  :else                     [:invalid (second vr) (nth vr 2) pos])))))))))

(defn decode
  "One bencode value from `ov` at `pos`. See `contract`."
  [ov pos]
  (if (>= pos (o/ocount ov))
    [:need-more pos]
    (let [b (o/oref ov pos)]
      (cond
        (= b I) (let [r (scan-int ov (inc pos) E)]
                  (cond
                    (= :need-more (first r)) [:need-more pos]
                    (= :invalid (first r))   [:invalid (second r) (nth r 2) pos]
                    :else                    [:ok (second r) (nth r 2)]))
        (= b L) (decode-list ov pos)
        (= b D) (decode-dict ov pos)
        (digit? b) (decode-bytes ov pos)
        :else [:invalid :unexpected-octet pos pos]))))

;; --- protocol-layer conveniences -------------------------------------------
;; Text decisions live here, above the codec, at fields known to be text.

(defn dget
  "Look up a string key in a decoded dict, whose keys are octet views."
  [m k]
  (get m (o/encode-utf8 k)))

(defn dtext
  "Decoded dict -> the value at string key `k` as a host string, or nil."
  [m k]
  (let [v (dget m k)]
    (when (o/octets? v) (o/->str v))))

(defn dstrs
  "Decoded value -> the same tree with octet views turned into host strings.
  Only for transcripts and for the differential oracle; no protocol logic reads
  through it."
  [v]
  (cond
    (o/octets? v) (o/->str v)
    (map? v)      (reduce (fn [m p] (assoc m (dstrs (first p)) (dstrs (second p)))) {} (seq v))
    (vector? v)   (mapv dstrs v)
    :else         v))
