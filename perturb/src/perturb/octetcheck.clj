(ns perturb.octetcheck
  "E37 / tally row 56, EXECUTED: `perturb.octet/octets?` decides the octet
  refinement rather than recognising a tag.

  IS A GATE. It records expectations and exits non-zero when one changes.

  WHY IT EXISTS. The hole was found by reading, not by a gate, in the namespace
  `-M:winbench` had just benchmarked. `octets?` tested `(contains? x K)`, so a
  hand-built `{:perturb.octet/octets <anything>}` was a window as far as every
  consumer was concerned — including `perturb.wire/socket`'s `:recv`, which
  declares `octets?` as its `:result-pred`, i.e. as the ONE check standing
  between a handler's return value and the codec. `perturb.effect/cross!` says
  of that check: `A handler that lies about its result type is stopped here, not
  downstream in the codec.` Section A below is that sentence under test.

  No socket, no server, no FFI. Section D times the predicate, because a
  recognizer that went from O(1) to O(n) owes the reader a number.

  WHAT THIS GATE DOES NOT CLAIM. It fixes ONE hole, found by ONE reading of ONE
  namespace. It is not a survey of `perturb.octet`, and nothing here says
  anything about whether other recognizers in perturb have the same shape."
  (:require [perturb.octet :as o]
            [perturb.effect :as fx]
            [perturb.wire :as w]
            [perturb.bencode :as b]))

(def ^:private K :perturb.octet/octets)
(def ^:private line (apply str (repeat 78 "=")))

(def ^:private failures (atom []))

(defn- check [nm expected actual]
  (if (= expected actual)
    (println (str "  ok   " nm))
    (do (swap! failures conj nm)
        (println (str "  FAIL " nm))
        (println (str "         expected " (pr-str expected)))
        (println (str "         actual   " (pr-str actual))))))

(defn- banner [s] (println) (println (str "-- " s " " (apply str (repeat (max 0 (- 74 (count s))) "-")))))

;; --- the corpus -------------------------------------------------------------
;; Left column: what a hand-forger writes. Right column: what it is.

(def forgeries
  [["{K [-1 999 256]}      out-of-range integers"  {K [-1 999 256]}      :element-not-an-octet]
   ["{K [104 -56]}         one signed byte"        {K [104 -56]}         :element-not-an-octet]
   ["{K [:a \"b\" nil]}      non-integers"           {K [:a "b" nil]}      :element-not-an-octet]
   ["{K \"hi\"}              a string backing"       {K "hi"}              :backing-not-a-vector]
   ["{K nil}               a nil backing"          {K nil}               :backing-not-a-vector]
   ["{K :not-a-sequence}   not even a sequence"    {K :not-a-sequence}    :backing-not-a-vector]
   ["{K (list 104 105)}    a seq, not a vector"    {K (list 104 105)}    :backing-not-a-vector]
   ["{K [104] :evil :pay}  a real window + a key"  {K [104] :evil :payload} :extra-keys]
   ["{}                    no backing key"         {}                    :missing-backing-key]
   ["[104 105]             a bare vector"          [104 105]             :not-a-map]])

(def legitimate
  [["(o/octets [])"                    (o/octets [])]
   ["o/empty-octets"                   o/empty-octets]
   ["(o/octets [0 127 128 255])"       (o/octets [0 127 128 255])]
   ["(o/octets (range 256))"           (o/octets (range 256))]
   ["(o/encode-utf8 \"café ☕\")"        (o/encode-utf8 "café ☕")]
   ["(o/str-> \"hi\")"                   (o/str-> "hi")]
   ["(o/osub (o/octets [1 2 3]) 1 3)"  (o/osub (o/octets [1 2 3]) 1 3)]
   ["(o/odrop (o/octets [1 2 3]) 3)"   (o/odrop (o/octets [1 2 3]) 3)]
   ["(o/oconcat a b)"                  (o/oconcat (o/octets [1]) (o/octets [2]))]
   ["(b/encode {\"k\" \"v\"})"             (b/encode {"k" "v"})]
   ["(o/from-signed [-56 127])"        (o/from-signed [-56 127])]
   ["a 4096-octet window"              (o/octets (mapv (fn [i] (mod i 256)) (range 4096)))]])

;; --- A. the effect boundary -------------------------------------------------

(defn- recv-once
  "Perform one `perturb.wire/socket :recv` against a handler that returns `v`,
  and report what the boundary did with it."
  [v]
  (try
    (fx/with-handlers
      {'perturb.wire/socket (fn [op _ _]
                              (cond (= op :connect) [:ok :token]
                                    (= op :recv)    [:ok v]
                                    :else           [:ok :closed]))}
      [:admitted (w/recv (w/connect "h" 1 :octetcheck/connect) 64 :octetcheck/recv)])
    (catch :default e
      [:refused (:perturb.effect/abort (ex-data e))])))

(defn- section-a []
  (banner "A. the effect boundary — a lying handler is stopped HERE")
  (println "  `perturb.wire/socket :recv` declares `:result-pred o/octets?`.")
  (println "  Each row installs a handler whose only lie is its recv result.")
  (println)
  (doseq [[label v _] forgeries]
    (check (str "recv refuses  " label) [:refused :invalid-result] (recv-once v)))
  (println)
  (let [good (o/octets [104 105])]
    (check "recv ADMITS a legitimate window (the positive control)"
           [:admitted good] (recv-once good))
    (check "recv ADMITS the empty window (end of stream must still work)"
           [:admitted o/empty-octets] (recv-once o/empty-octets))))

;; --- B. the recognizer, directly --------------------------------------------

(defn- section-b []
  (banner "B. the recognizer — negative control and positive control")
  (doseq [[label v reason] forgeries]
    (check (str "octets? rejects  " label) false (o/octets? v))
    (check (str "  reason is " (name reason))
           reason (:perturb.octet/reason (o/octets-violation v))))
  (println)
  (doseq [[label v] legitimate]
    (check (str "octets? accepts  " label) true (o/octets? v))
    (check (str "  no violation   " label) nil (o/octets-violation v)))
  (println)
  (check "octets? and octets-violation agree on every corpus value (no drift)"
         []
         (vec (keep (fn [[label v]]
                      (when (not= (o/octets? v) (nil? (o/octets-violation v))) label))
                    (concat (map (fn [r] [(first r) (second r)]) forgeries)
                            legitimate)))))

;; --- C. the two consequences the reproduction measured ----------------------

(defn- section-c []
  (banner "C. what the hole cost, restated as expectations")
  (println "  1. VALUE EQUALITY. `perturb.bencode/dget` looks a decoded dict key")
  (println "     up by `(o/encode-utf8 k)`, i.e. by VALUE. Under the tag test a")
  (println "     window carrying an extra key rendered as \"hi\" and hashed as")
  (println "     something else, so the lookup silently missed.")
  (let [honest (o/octets [104 105])
        forged {K [104 105] :evil :payload}]
    (check "both render as \"hi\"" ["hi" "hi"] [(o/->str honest) (o/->str forged)])
    (check "they are NOT =" false (= honest forged))
    (check "dget finds the honest key" "v" (b/dget {honest "v"} "hi"))
    (check "dget missed the forged key — that is the silent failure" nil (b/dget {forged "v"} "hi"))
    (check "and the forged key is now refused as a window" false (o/octets? forged)))
  (println)
  (println "  2. LAUNDERING. `oconcat`/`osub` do not re-check, so a forged")
  (println "     operand used to produce a value that looked constructor-built.")
  (println "     Still true INSIDE the namespace; the boundary is what changed.")
  (let [forged {K [-1 999]}]
    (check "a forged operand still launders through oconcat"
           [65 -1 999] (o/ovec (o/oconcat (o/octets [65]) forged)))
    (check "but the laundered result does not pass octets? either"
           false (o/octets? (o/oconcat (o/octets [65]) forged)))
    (check "so it cannot re-enter through the effect boundary"
           [:refused :invalid-result] (recv-once (o/oconcat (o/octets [65]) forged)))))

;; --- D. the cost ------------------------------------------------------------
;; E36 measured this representation at ~158 ns/octet for `oref`. A validation
;; added to the per-octet hot path would be a real regression, so the number
;; that matters is: what does the recognizer cost, and is it on that path?

(defn- tag-only?
  "The predicate as it was, kept here so the cost is a DIFFERENCE and not an
  absolute. Nothing dispatches on it."
  [x] (and (map? x) (contains? x K)))

(defn- every?-variant
  "The scan written with `every?` — 2.6× faster and REJECTED, because it
  allocates and `dev/verify-noio.sh` counts the resulting `mmap` as two
  attributable syscalls in a window whose recorded expectation is zero.
  Kept here so the price of the allocation-free loop is a number rather than an
  assertion. Nothing dispatches on it."
  [x]
  (and (map? x) (= 1 (count x))
       (let [v (get x K ::absent)]
         (and (vector? v) (every? o/octet? v)))))

(defn- time-ns [passes f]
  (let [t0 (System/nanoTime)]
    (loop [i 0] (when (< i passes) (f) (recur (inc i))))
    (/ (double (- (System/nanoTime) t0)) passes)))

(defn- spread [xs]
  (let [s (sort xs) n (count s)]
    [(nth s (quot n 2)) (/ (- (last s) (first s)) (max 1e-9 (nth s (quot n 2))))]))

(defn- section-d [samples]
  (banner "D. the cost of the change")
  (println "  `octets?` went from O(1) to O(n). It is NOT on the per-octet hot")
  (println "  path: no operation -M:winbench measures calls it. `oref`, `osub`,")
  (println "  `odrop`, `oconcat`, `ocount` and `decode-utf8` reach the backing")
  (println "  vector through `ovec`, which is untouched. Grep is the evidence;")
  (println "  the timings below are the size of the thing that DID change.")
  (println)
  (println "  For scale: E36 measured `oref` at ~158 ns/octet and a bare `nth`")
  (println "  at ~51 on this representation. Validating a window is one `nth`")
  (println "  pass plus an `octet?` call per element, and it is paid once per")
  (println "  `recv` rather than once per octet read.")
  (println)
  (println (str "  " (format "%-14s" "size (octets)")
                (format "%13s" "tag-only ns") (format "%14s" "shipped ns")
                (format "%11s" "ns/octet") (format "%9s" "spread")
                (format "%16s" "every? ns") (format "%11s" "ns/octet")))
  (doseq [n [0 8 64 1024 8192]]
    (let [ov     (o/octets (mapv (fn [i] (mod i 256)) (range n)))
          passes (max 200 (quot 2000000 (max 1 n)))
          old    (first (spread (repeatedly samples (fn [] (time-ns passes (fn [] (tag-only? ov)))))))
          [new sp] (spread (repeatedly samples (fn [] (time-ns passes (fn [] (o/octets? ov))))))
          ev     (first (spread (repeatedly samples (fn [] (time-ns passes (fn [] (every?-variant ov)))))))]
      (println (str "  " (format "%-14s" (str n))
                    (format "%13.1f" old) (format "%14.1f" new)
                    (format "%11.2f" (if (zero? n) 0.0 (/ (- new old) n)))
                    (format "%8.1f%%" (* 100 sp))
                    (format "%16.1f" ev)
                    (format "%11.2f" (if (zero? n) 0.0 (/ (- ev old) n)))))))
  (println)
  (println "  THE `every?` COLUMN IS THE REJECTED VARIANT, and it is faster. It")
  (println "  is `reduce`+`reduced`, which drives the vector's index loop instead")
  (println "  of walking the trie per element — but it ALLOCATES, and")
  (println "  `dev/verify-noio.sh` turned the scripted run's marker window from")
  (println "  0 attributable syscalls into 2 anonymous `mmap`s (Chez growing its")
  (println "  heap), twice out of two runs, against 0 twice out of two for the")
  (println "  loop that shipped. INHERITED I11's instrument cannot tell heap")
  (println "  growth from I/O, so the allocation-free scan is the one on the")
  (println "  `recv` boundary, and the two right-hand columns are what that")
  (println "  choice costs.")
  (println)
  (println "  The rejection path allocates nothing — `perturb.bencode/enc` calls")
  (println "  `octets?` on every integer, string and keyword it encodes:")
  (doseq [[label v] [["an integer" 42] ["a string" "hello"] ["a keyword" :k]
                     ["a 64-key map" (zipmap (range 64) (range 64))]]]
    (let [[t sp] (spread (repeatedly samples (fn [] (time-ns 200000 (fn [] (o/octets? v))))))]
      (println (str "    " (format "%-16s" label) (format "%8.1f" t) " ns"
                    (format "   spread %.1f%%" (* 100 sp)))))))

;; --- E. the paths that call it, end to end ----------------------------------

(defn- section-e []
  (banner "E. the callers still work")
  (check "bencode round-trips a nested value"
         {"a" "x" "b" [1 2] "c" {"d" "y"}}
         (b/dstrs (second (b/decode (b/encode {"a" "x" "b" [1 2] "c" {"d" "y"}}) 0))))
  (check "dtext reads a text field" "eval"
         (b/dtext (second (b/decode (b/encode {"op" "eval"}) 0)) "op"))
  (check "utf-8 survives the round trip" "café ☕"
         (o/->str (o/octets (o/ovec (o/encode-utf8 "café ☕"))))))

(defn run [samples]
  (reset! failures [])
  (println line)
  (println "perturb.octetcheck — E37 / tally row 56: `octets?` decides the octet")
  (println "  refinement instead of recognising a tag. A forged tagged map is")
  (println "  refused at `perturb.wire/socket :recv`, where `perturb.effect`")
  (println "  already promised it would be.")
  (println line)
  (section-a)
  (section-b)
  (section-c)
  (section-e)
  (section-d samples)
  (println)
  (println line)
  (if (empty? @failures)
    (do (println "VERDICT ok — every forgery refused, every legitimate window admitted.")
        (println line)
        true)
    (do (println (str "VERDICT FAILED — " (count @failures) " expectation(s) changed:"))
        (doseq [f @failures] (println (str "  " f)))
        (println line)
        false)))

(defn -main [& args]
  (let [samples (if (seq args) (Integer/parseInt (first args)) 5)]
    (if (run samples) (System/exit 0) (System/exit 1))))
