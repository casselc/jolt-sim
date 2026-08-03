(ns perturb.oracle
  "Differential oracle: perturb's bencode against `jolt.nrepl`'s, over the
  profile where the two overlap.

  WHY THIS IS A VALID ORACLE AND WHAT IT CANNOT SAY. `jolt.nrepl/bencode` and
  `bdecode` (jolt-core/jolt/nrepl.clj:128 and :139) are an independently written
  implementation of the same wire format, reached here through `resolve` because
  they are private. SHAREABLE S7 records the reasoning: the implementation is
  not shareable — it is latin1-string-based, which is the convention perturb
  declines — but the ORACLE ROLE is neutral, and §1.6 says the same about the
  existing corpora (`valid as VALUE tests`).

  The limit is the important part: an oracle can only test the overlap. It is
  silent about every place perturb diverges, which is to say about everything
  this artifact exists to demonstrate. Agreement here means perturb's codec is
  not wrong in a way jolt's codec is also not wrong. It does not mean the codec
  is right.

  THE BRIDGE. jolt's encoder returns a host string in which one character is one
  wire octet (its `->wire` maps UTF-8 bytes into ISO-8859-1 characters). So
  `(map int s)` recovers the octets — without a byte array and without a fold,
  because the octets were never signed on that path either. That bridge is the
  only jolt-specific code in this namespace."
  (:require [perturb.octet :as o]
            [perturb.bencode :as b]))

(def ^:private jolt-encode (delay @(resolve 'jolt.nrepl/bencode)))
(def ^:private jolt-decode (delay @(resolve 'jolt.nrepl/bdecode)))

(defn- jolt-octets
  "jolt's latin1 wire string -> octet view."
  [s] (o/octets (mapv int s)))

(defn- octets->jolt-wire
  "octet view -> the latin1 wire string jolt's bdecode expects."
  [ov] (apply str (map char (o/oseq ov))))

(defn jolt-view
  "Render a perturb-decoded tree the way `jolt.nrepl/bdecode` renders the same
  bytes.

  ORACLE FINDING, and the reason this function exists. jolt's decoder is
  ASYMMETRIC: it UTF-8-decodes dictionary KEYS (`(assoc acc (wire-> (first k))
  ...)`, nrepl.clj:157) and leaves every other byte string as a latin1 string,
  one character per octet. Its handler then decodes the fields it knows to be
  text (`(wire-> (get request \"code\"))`, nrepl.clj:320).

  That is not a bug and perturb does not diverge from it — it is the same
  layering perturb takes deliberately: a bencode string is bytes, and text is a
  decision made at the field the protocol knows to be text. jolt just draws the
  line at dict keys instead of at nothing. So the oracle must model it, and the
  round trip through jolt's decoder is compared against this rendering rather
  than against the original value. Comparing against the original would report a
  disagreement that is really a difference in where the two put the text
  boundary."
  [v key-position]
  (cond
    (o/octets? v) (if key-position (o/->str v) (octets->jolt-wire v))
    (map? v)      (reduce (fn [m p] (assoc m (jolt-view (first p) true) (jolt-view (second p) false)))
                          {} (seq v))
    (vector? v)   (mapv (fn [x] (jolt-view x false)) v)
    :else         v))

(def corpus
  "Values in the overlap: integers, strings, vectors, and maps with string keys.
  Excluded deliberately — raw byte strings above 0x7f (perturb has a type for
  them, jolt does not), nil (jolt encodes it as `0:`, indistinguishable from
  the empty string, INHERITED I13), and doubles (I9)."
  [0
   1
   -1
   42
   -1234567890123456789
   ""
   "clone"
   "(+ 1 2)"
   "λ-and-ÿ"
   []
   ["a"]
   ["a" 1 ["b" 2] []]
   {}
   {"op" "clone"}
   {"op" "eval" "code" "(+ 1 2)" "id" "3"}
   {"status" ["done"] "value" "3" "ns" "user"}
   {"b" 2 "a" 1 "c" 3}
   {"z" {"y" ["x" 1]} "a" []}
   {"unicode" "λ" "id" "7"}
   {"ops" {"clone" {} "eval" {} "describe" {}} "status" ["done"]}])

(defn run []
  (let [failures (atom [])
        note (fn [what v extra]
               (swap! failures conj {:case v :what what :detail extra})
               (println (str "  FAIL " what " on " (pr-str v)))
               (println (str "        " (pr-str extra))))]
    (println "== differential oracle: perturb.bencode vs jolt.nrepl (jolt-core/jolt/nrepl.clj:128) ==")
    (doseq [v corpus]
      (let [p-ov (b/encode v)
            j-s  (@jolt-encode v)
            j-ov (jolt-octets j-s)]
        ;; 1. encoders agree octet for octet
        (if (= (o/ovec p-ov) (o/ovec j-ov))
          nil
          (note "encoders disagree" v {:perturb (o/hex p-ov) :jolt (o/hex j-ov)}))
        ;; 2. perturb decodes jolt's bytes back to the original value
        (let [r (b/decode j-ov 0)]
          (cond
            (not= :ok (first r)) (note "perturb cannot decode jolt's output" v r)
            (not= (o/ocount j-ov) (nth r 2)) (note "perturb did not consume the whole frame" v r)
            (not= v (b/dstrs (second r))) (note "perturb decoded jolt's output to a different value" v
                                                {:got (b/dstrs (second r))})))
        ;; 3. jolt decodes perturb's bytes to the tree jolt's own text boundary
        ;;    produces (see `jolt-view`)
        (let [r        (@jolt-decode (octets->jolt-wire p-ov) 0)
              expected (jolt-view (second (b/decode p-ov 0)) false)]
          (cond
            (nil? r) (note "jolt cannot decode perturb's output" v nil)
            (not= (o/ocount p-ov) (second r)) (note "jolt did not consume the whole frame" v r)
            (not= expected (first r)) (note "jolt decoded perturb's output to a different value" v
                                            {:got (first r) :expected expected})))))
    (println (str "  " (count corpus) " values, "
                  (count @failures) " disagreement(s)"))
    (println)
    (println "  checks per value:")
    (println "    1. encoders agree octet for octet")
    (println "    2. perturb decodes jolt's octets back to the original value")
    (println "    3. jolt decodes perturb's octets to jolt's own rendering of them")
    (println)
    (println "  scope: this compares the OVERLAP only. It says nothing about")
    (println "  perturb's raw byte-string type, its octet decoding of bencode")
    (println "  strings, or any divergence in PERTURB-DESIGN §2. Check 3 needed a")
    (println "  model of jolt's asymmetric text boundary (see `jolt-view`); that")
    (println "  asymmetry is an oracle finding, not a disagreement.")
    (empty? @failures)))

(defn -main [& _]
  (require 'jolt.nrepl)
  (if (run) (System/exit 0) (System/exit 1)))
