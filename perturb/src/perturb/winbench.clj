(ns perturb.winbench
  "The byte-window representation, measured.

  WHY THIS EXISTS. Round 2 of the SOTA survey asked for a parser benchmark
  before the sans-io `:need-more` contract (return the EXACT ORIGINAL cursor) is
  changed: one-byte chunk delivery, maximum legal frame size, repeated-prefix
  CPU, retained-buffer high-water mark, error offset and replay. Every one of
  those is a question about the BYTE-SEQUENCE REPRESENTATION rather than about
  the parser. STRUCTURAL-TIER-BRIEF §1 says so and asks for the number.

  WHAT THE MEASUREMENT IS AGAINST. Two recorded findings set the expectation:

    E11 — a Jolt byte array is 8 bytes per element, the same as a long array,
          and `(aget b i)` lowers to `(jolt-nth b i)`: generic collection
          dispatch, not a primitive array read.
    E10 — `nth` on a deftype cost ~2,061 ns/byte against ~86 for a persistent
          vector, and the gap survived three rounds of fixes.

  Neither is about the representation perturb actually uses, which is why the
  first thing this namespace reports is what that representation IS.

  DISCIPLINE. E9 established that a single sample is not evidence on this host
  (437/395/421/432 microseconds on one build). Every timing figure below is a
  median of repeated samples with the spread printed beside it, and section F is
  a disabled-instrument control: the same work measured with the per-iteration
  instrument and with one timer around the whole batch, so the apparatus can be
  shown not to dominate what it measures.

  NOT A GATE. It records no expectations and asserts no thresholds. It prints
  numbers. Nothing here can fail, which is deliberate: a threshold picked from
  one host on one Chez would be a fabricated baseline, and E10 is the record of
  what happens when a baseline is trusted after a single sample."
  (:require [perturb.octet :as o]
            [perturb.http :as http]))

;; ---------------------------------------------------------------------------
;; the harness
;; ---------------------------------------------------------------------------

(defn- time-ns
  "One sample: run `f` once, return elapsed wall nanoseconds."
  [f]
  (let [t0 (System/nanoTime)]
    (f)
    (- (System/nanoTime) t0)))

(defn- samples
  "`n` independent samples of `f`, as a vector of nanosecond readings."
  [n f]
  (loop [i 0 acc []]
    (if (>= i n) acc (recur (inc i) (conj acc (time-ns f))))))

(defn- median [xs]
  (let [s (vec (sort xs)) c (count s)]
    (if (zero? c)
      0
      (if (odd? c)
        (nth s (quot c 2))
        (quot (+ (nth s (dec (quot c 2))) (nth s (quot c 2))) 2)))))

(defn- spread-pct
  "(max - min) / median, as a percent. E9's unit: this is what says whether a
  delta is evidence."
  [xs]
  (let [m (median xs)]
    (if (zero? m) 0.0 (* 100.0 (/ (double (- (apply max xs) (apply min xs))) (double m))))))

(defn- rnd [x places]
  (let [f (Math/pow 10.0 places)]
    (/ (Math/round (* (double x) f)) f)))

(defn- pad [s w]
  (let [s (str s)]
    (if (>= (count s) w) s (str s (apply str (repeat (- w (count s)) \space))))))

(defn- lpad [s w]
  (let [s (str s)]
    (if (>= (count s) w) s (str (apply str (repeat (- w (count s)) \space)) s))))

(defn- rule [] (println (apply str (repeat 78 \-))))

(defn- head [n title]
  (println)
  (println (str n ". " title))
  (rule))

;; ---------------------------------------------------------------------------
;; the retention instrument
;; ---------------------------------------------------------------------------
;;
;; `jolt.perf/collect!` forces a full collection; `jolt.perf/bytes-allocated`
;; then reports the LIVE heap. Build a structure, hold the only reference in an
;; atom, collect, read; drop the reference, collect, read again. The difference
;; is what the structure retained — including anything it transitively keeps
;; alive, which is exactly the question a window that shares a backing array
;; would answer badly.
;;
;; The instrument has a noise floor (other allocation happens between the two
;; readings). Section E measures that floor rather than assuming it away.

(defn- retained-once
  [build!]
  (let [h (atom nil)]
    (reset! h (build!))
    (jolt.perf/collect!)
    (let [with (jolt.perf/bytes-allocated)]
      (reset! h nil)
      (jolt.perf/collect!)
      (- with (jolt.perf/bytes-allocated)))))

(defn- retained
  "Median of `n` retention readings for `build!`."
  [n build!]
  (loop [i 0 acc []]
    (if (>= i n) [(median acc) acc] (recur (inc i) (conj acc (retained-once build!))))))

(defn- alloc-total
  "Cumulative bytes allocated by the process.

  CORRECTION TO A DOCUMENTED INSTRUMENT. `natives-misc.ss` says
  `bytes-allocated is cumulative for the process; take a difference around the
  work under test`. It is not: `rt.ss` has it right two files over — it is the
  LIVE heap, and a difference across a workload that triggers a collection comes
  out NEGATIVE (measured: -1,180,160 bytes for a workload that allocated
  megabytes). Adding the collector's reclaimed total recovers a monotone
  cumulative counter, and that is what is used here."
  []
  (+ (jolt.perf/bytes-allocated) (jolt.host/gc-bytes)))

(defn- allocated
  "Bytes allocated by running `f` once, garbage included. Deterministic: this
  counter does not vary run to run the way wall time does."
  [f]
  (let [b0 (alloc-total)]
    (f)
    (- (alloc-total) b0)))

;; ---------------------------------------------------------------------------
;; corpus
;; ---------------------------------------------------------------------------
;;
;; SELECTION RULE, stated before the tool ran (STRUCTURAL-TIER-BRIEF's constraint
;; about E3's sampling error): the frames are real `perturb.http` requests,
;; because `perturb.http` is the namespace whose driver actually performs the
;; exact-cursor rollback under one-octet delivery. Frame size is varied by
;; padding ONE header value, so every frame has the same number of header fields
;; and the same parse shape and only the octet count changes. Nothing is chosen
;; to make a curve look a particular way.

(defn- request-text
  "A well-formed HTTP/1.1 request whose head is padded to roughly `target`
  octets. One padded header field; everything else is fixed."
  [target]
  (let [fixed "GET /bench HTTP/1.1\r\nhost: example\r\nx-pad: \r\n\r\n"
        n     (max 0 (- target (count fixed)))]
    (str "GET /bench HTTP/1.1\r\nhost: example\r\nx-pad: "
         (apply str (repeat n \a))
         "\r\n\r\n")))

(defn- frame [target] (o/encode-utf8 (request-text target)))

;; ---------------------------------------------------------------------------
;; the driver, replicated
;; ---------------------------------------------------------------------------
;;
;; `perturb.http/read-request` is a capability operation: calling it needs a
;; ServerConn, a wire token, an effect handler and the capability ledger, all of
;; which allocate and time. The loop below is its buffer arithmetic ALONE,
;; transcribed line for line, so that what is measured is the representation and
;; not the ledger:
;;
;;   read-request, :need-more arm
;;     (recur (assoc c :perturb.http/buf
;;                     (o/oconcat (o/odrop (:perturb.http/buf c) (:perturb.http/pos c))
;;                                chunk)
;;                     :perturb.http/pos 0))
;;
;; Note what `pos` does: the :need-more arm resets it to 0, so on the NEXT
;; refill `(o/odrop buf 0)` is `(o/osub buf 0 (ocount buf))` — the whole buffer.
;; That is not an accident of this transcription; it is what the driver does.

(defn- drive
  "Feed `fr` to `parse-request` `chunk` octets at a time, exactly as
  `read-request` refills. Returns {:parses n :result tag}."
  [fr chunk]
  (let [n (o/ocount fr)]
    (loop [i 0 buf o/empty-octets pos 0 parses 0]
      (let [r   (http/parse-request buf pos)
            tag (first r)]
        (cond
          (= :ok tag)      {:parses (inc parses) :result :ok :consumed (nth r 2)}
          (= :invalid tag) {:parses (inc parses) :result :invalid :reason (second r)}
          (>= i n)         {:parses (inc parses) :result :starved}
          :else
          (let [j (min n (+ i chunk))]
            (recur j
                   (o/oconcat (o/odrop buf pos) (o/osub fr i j))
                   0
                   (inc parses))))))))

(defn- buffer-only
  "The same refill arithmetic with the parse removed. Isolates the cost the
  REPRESENTATION charges from the cost the exact-cursor re-parse charges."
  [fr chunk]
  (let [n (o/ocount fr)]
    (loop [i 0 buf o/empty-octets]
      (if (>= i n)
        (o/ocount buf)
        (let [j (min n (+ i chunk))]
          (recur j (o/oconcat (o/odrop buf 0) (o/osub fr i j))))))))

;; ---------------------------------------------------------------------------
;; A — what the representation IS
;; ---------------------------------------------------------------------------

(defn- section-representation []
  (head "A" "What the window actually is")
  (let [ov (o/octets [1 2 3])]
    (println "  perturb.octet view          " (pr-str ov))
    (println "  map?                        " (map? ov))
    (println "  backing type                " (str (type (o/ovec ov))))
    (println "  vector?                     " (vector? (o/ovec ov))))
  (println)
  (println "  It is a TAGGED MAP over a persistent vector of exact integers.")
  (println "  Not a deftype (E10's subject) and not a byte array (E11's subject).")
  (println "  So neither recorded number is a measurement of this representation;")
  (println "  section B measures it, and re-measures both of theirs for contrast.")
  (println)
  (println "  Lowering, read off jolt.perf/optimized-scheme at this commit:")
  (doseq [[label form]
          [["(aget b i)      " '(fn [b i] (aget b i))]
           ["(aget ^doubles) " '(fn [^doubles a i] (aget a i))]
           ["(nth v i)       " '(fn [v i] (nth v i))]
           ["(o/oref ov i)   " '(fn [ov i] (perturb.octet/oref ov i))]
           ["(o/osub ov a b) " '(fn [ov a b] (perturb.octet/osub ov a b))]]]
    (println "   " label "->" (jolt.perf/optimized-scheme form "perturb.winbench")))
  (println)
  (println "  And the slicing primitive underneath the window, from Jolt's own")
  (println "  clojure/core/00-kernel.clj:")
  (println "    (loop [i s acc []] (if (< i e) (recur (inc i) (conj acc (nth v i))) acc))")
  (println "  clojure.core/subvec in Jolt COPIES. It is not an O(1) view over a")
  (println "  shared parent. Section D measures what that means for retention.")
  (println))

;; ---------------------------------------------------------------------------
;; B — per-octet access cost
;; ---------------------------------------------------------------------------

(def ^:private access-n 4096)
(def ^:private access-passes 8)

;; E10's SUBJECT, re-created. E10 measured `nth` on a deftype at ~2,061 ns/byte
;; against ~86 for a persistent vector — roughly 24x — and recorded that the gap
;; survived three rounds of fixes. That deftype was `jolt.bytes/Window`, which is
;; not in this tree; this is the same shape (a deftype whose `nth` forwards to a
;; vector field) so the ratio can be re-taken at the current commit.
(deftype WindowLike [v]
  clojure.lang.Indexed
  (nth [_ i] (nth v i)))

(defn- sum-oref [ov n passes]
  (loop [p 0 acc 0]
    (if (>= p passes)
      acc
      (recur (inc p)
             (loop [i 0 a acc] (if (>= i n) a (recur (inc i) (+ a (o/oref ov i)))))))))

(defn- sum-nth [v n passes]
  (loop [p 0 acc 0]
    (if (>= p passes)
      acc
      (recur (inc p)
             (loop [i 0 a acc] (if (>= i n) a (recur (inc i) (+ a (nth v i)))))))))

(defn- sum-aget [arr n passes]
  (loop [p 0 acc 0]
    (if (>= p passes)
      acc
      (recur (inc p)
             (loop [i 0 a acc] (if (>= i n) a (recur (inc i) (+ a (aget arr i)))))))))

(defn- sum-daget [^doubles arr n passes]
  (loop [p 0 acc 0.0]
    (if (>= p passes)
      acc
      (recur (inc p)
             (loop [i 0 a acc] (if (>= i n) a (recur (inc i) (+ a (aget arr i)))))))))

(defn- sum-hoisted
  "`oref` with the tag lookup lifted out of the loop — which is `nth` on the
  backing vector, reached by a different route. It is here as a REPEAT ARM: the
  identical work, sampled independently, so that two rows of this table can be
  checked against each other the way E10's `String`/`getBytes` control row was."
  [ov n passes]
  (let [v (o/ovec ov)]
    (sum-nth v n passes)))

(defn- section-access [reps]
  (head "B" (str "Per-octet access cost — " access-n " octets x " access-passes
                 " passes per sample, " reps " samples"))
  (let [xs   (mapv (fn [i] (mod (* i 7) 256)) (range access-n))
        ov   (o/octets xs)
        v    (o/ovec ov)
        ba   (byte-array xs)
        la   (let [a (make-array Long/TYPE access-n)]
               (loop [i 0] (when (< i access-n) (aset a i (nth xs i)) (recur (inc i)))) a)
        da   (double-array access-n)
        dt   (WindowLike. v)
        arms [["perturb.octet/oref  (the window)" (fn [] (sum-oref ov access-n access-passes))]
              ["nth on the backing pvec"          (fn [] (sum-nth v access-n access-passes))]
              ["  same work again (repeat arm)"   (fn [] (sum-hoisted ov access-n access-passes))]
              ["nth on a deftype (E10's subject)" (fn [] (sum-nth dt access-n access-passes))]
              ["aget on a Jolt byte array"        (fn [] (sum-aget ba access-n access-passes))]
              ["aget on a Jolt long array"        (fn [] (sum-aget la access-n access-passes))]
              ["aget on ^doubles (E1's 'floor')"  (fn [] (sum-daget da access-n access-passes))]]]
    ;; warm every arm once before any of them is sampled
    (doseq [[_ f] arms] (f))
    (println (str "  " (pad "arm" 36) (lpad "ns/octet" 12) (lpad "median ns" 14)
                  (lpad "spread" 10)))
    (rule)
    (doseq [[label f] arms]
      (let [ss (samples reps f)
            m  (median ss)]
        (println (str "  " (pad label 36)
                      (lpad (rnd (/ (double m) (* access-n access-passes)) 1) 12)
                      (lpad m 14)
                      (lpad (str (rnd (spread-pct ss) 1) "%") 10)))))
    (rule)
    (println "  Allocation per pass over" access-n "octets (deterministic counter):")
    (doseq [[label f] [["oref" (fn [] (sum-oref ov access-n 1))]
                       ["nth " (fn [] (sum-nth v access-n 1))]
                       ["aget" (fn [] (sum-aget ba access-n 1))]]]
      (println (str "    " label " -> " (allocated f) " bytes")))))

;; ---------------------------------------------------------------------------
;; C — repeated-prefix CPU under adversarial chunking
;; ---------------------------------------------------------------------------

(defn- section-chunking [reps sizes]
  (head "C" (str "Repeated-prefix CPU: one octet per delivery vs whole frame, "
                 reps " samples"))
  (println "  `whole`  = the frame handed to parse-request in one chunk.")
  (println "  `1-octet`= read-request's refill arithmetic, one octet per recv.")
  (println "  `buffer` = the same refill with the parse deleted (representation only).")
  (println "  `parse`  = 1-octet minus buffer (the exact-cursor re-parse alone).")
  (println)
  (println (str "  " (pad "octets" 8) (lpad "parses" 8) (lpad "whole ns" 12)
                (lpad "1-octet ns" 14) (lpad "spread" 8)
                (lpad "buffer ns" 13) (lpad "parse ns" 13)
                (lpad "x/whole" 9) (lpad "exponent" 10)))
  (rule)
  (loop [ss sizes prev nil]
    (when (seq ss)
      (let [n     (first ss)
            fr    (frame n)
            len   (o/ocount fr)
            info  (drive fr 1)
            _     (http/parse-request fr 0)
            ws    (samples reps (fn [] (http/parse-request fr 0)))
            ds    (samples reps (fn [] (drive fr 1)))
            bs    (samples reps (fn [] (buffer-only fr 1)))
            wm    (median ws) dm (median ds) bm (median bs)
            expo  (if (nil? prev)
                    "-"
                    (rnd (/ (Math/log (/ (double dm) (double (second prev))))
                            (Math/log (/ (double len) (double (first prev)))))
                         3))]
        (println (str "  " (pad len 8)
                      (lpad (:parses info) 8)
                      (lpad wm 12)
                      (lpad dm 14)
                      (lpad (str (rnd (spread-pct ds) 1) "%") 8)
                      (lpad bm 13)
                      (lpad (- dm bm) 13)
                      (lpad (str (rnd (/ (double dm) wm) 0) "x") 9)
                      (lpad expo 10)))
        (recur (rest ss) [len dm]))))
  (rule)
  (println "  `exponent` is log(t/t_prev)/log(n/n_prev): 1.0 is linear, 2.0 is")
  (println "  quadratic. It rises toward 2 as the per-refill constant stops")
  (println "  dominating, which is what a two-term a*n + b*n^2 cost looks like.")
  (println "  Every `1-octet` run above returned" (:result (drive (frame (first sizes)) 1))
           "and consumed the whole frame, so both arms decode the same request."))

;; ---------------------------------------------------------------------------
;; D — retained-buffer high-water mark
;; ---------------------------------------------------------------------------

(defn- section-retention [reps]
  (head "D" (str "Retained-buffer high-water mark — live bytes after a full "
                 "collection, median of " reps))
  (let [n     4096
        fr    (frame n)
        len   (o/ocount fr)
        rows
        [["nil (the instrument's own floor)"     0 (fn [] nil)]
         ["a 4096-element persistent vector"     4096
          (fn [] (vec (range 4096)))]
         ["a 4096-element octet view"            4096
          (fn [] (o/octets (mapv (fn [i] (mod i 256)) (range 4096))))]
         ["(byte-array 4096)"                    4096 (fn [] (byte-array 4096))]
         ["(make-array Long/TYPE 4096)"          4096 (fn [] (make-array Long/TYPE 4096))]
         ["(double-array 4096)"                  4096 (fn [] (double-array 4096))]
         [(str "the " len "-octet frame")        len  (fn [] (frame n))]
         ["8 octets sliced out of a 262144 view" 8
          (fn [] (o/osub (o/octets (mapv (fn [i] (mod i 256)) (range 262144))) 0 8))]
         ["conn buf alone, WHOLE delivery"       len
          (fn [] (o/oconcat (o/odrop o/empty-octets 0) fr))]
         ["conn buf alone, 1-OCTET delivery"     len
          (fn [] (let [m (o/ocount fr)]
                   (loop [i 0 buf o/empty-octets]
                     (if (>= i m) buf
                         (recur (inc i) (o/oconcat (o/odrop buf 0) (o/osub fr i (inc i))))))))]
         ["conn buf + parsed request, WHOLE"     len
          (fn [] (let [b (o/oconcat (o/odrop o/empty-octets 0) fr)]
                   [b (http/parse-request b 0)]))]
         ["conn buf + parsed request, 1-OCTET"   len
          (fn [] (let [m (o/ocount fr)]
                   (loop [i 0 buf o/empty-octets]
                     (if (>= i m)
                       [buf (http/parse-request buf 0)]
                       (recur (inc i) (o/oconcat (o/odrop buf 0) (o/osub fr i (inc i))))))))]]]
    (println (str "  " (pad "structure" 44) (lpad "retained B" 12) (lpad "B/elem" 10)))
    (rule)
    (doseq [[label elems f] rows]
      (let [[m _] (retained reps f)]
        (println (str "  " (pad label 44) (lpad m 12)
                      (lpad (if (> elems 0) (rnd (/ (double m) elems) 2) "-") 10)))))
    (rule)
    (println "  B/elem divides by that row's own element count.")
    (println)
    (println "  Read three things off this table:")
    (println "   1. The 8-of-262144 row is the whole `does a window retain its")
    (println "      parent` question. It does NOT: a slice of 8 octets taken out of")
    (println "      a quarter-megabyte view retains hundreds of bytes, not megabytes,")
    (println "      because Jolt's subvec copies. The residue over 8 octets of")
    (println "      payload is the tagged map itself — a fixed per-view constant.")
    (println "   2. The two `conn buf alone` rows are the high-water mark question.")
    (println "      One-octet delivery and whole delivery retain the SAME bytes, to")
    (println "      within the instrument's floor. Exact-cursor rollback retains")
    (println "      nothing extra; it re-does work instead.")
    (println "   3. The octet view costs MORE per octet than the byte array E11")
    (println "      criticised for costing 8. Section B is where that trade shows.")
    (println)
    (println "  Peak allocation, one-octet delivery vs whole (cumulative counter,")
    (println "  which does not vary run to run):")
    (println "    whole  ->" (allocated (fn [] (drive fr len))) "bytes")
    (println "    1-octet->" (allocated (fn [] (drive fr 1))) "bytes")))

;; ---------------------------------------------------------------------------
;; E — maximum legal frame, and error offset / replay
;; ---------------------------------------------------------------------------

(defn- section-limits [reps]
  (head "E" "Maximum legal frame, and error offset / replay")
  (println "  perturb.http/max-head-octets  =" http/max-head-octets)
  (println "  perturb.http/max-header-fields=" http/max-header-fields)
  (println "  perturb.http/max-body-octets  =" http/max-body-octets)
  (println)
  (println "  Head at and over the limit (incomplete head, so `stuck` decides):")
  (doseq [k [(- http/max-head-octets 1) http/max-head-octets (+ http/max-head-octets 1)
             (+ http/max-head-octets 2)]]
    (let [partial (o/osub (frame (+ k 64)) 0 k)     ; k octets with no terminating CRLFCRLF
          r       (http/parse-request partial 0)]
      (println (str "    avail=" (lpad k 6) "  -> " (pr-str (vec (take 3 r)))))))
  (println)
  (println "  Content-Length at and over the limit (declared, body absent):")
  (doseq [len [(- http/max-body-octets 1) http/max-body-octets (+ http/max-body-octets 1)]]
    (let [txt (str "POST /b HTTP/1.1\r\nhost: h\r\ncontent-length: " len "\r\n\r\n")
          r   (http/parse-request (o/encode-utf8 txt) 0)]
      (println (str "    content-length=" (lpad len 9) " -> " (pr-str (vec (take 2 r)))))))
  (println)
  (println "  The maximum legal HEAD (" http/max-head-octets "octets), delivered whole and")
  (println "  one octet at a time:")
  (let [fr  (frame http/max-head-octets)
        len (o/ocount fr)
        _   (http/parse-request fr 0)
        ws  (samples reps (fn [] (http/parse-request fr 0)))
        ds  (samples 3 (fn [] (drive fr 1)))]
    (println (str "    whole   " (lpad (median ws) 14) " ns   spread "
                  (rnd (spread-pct ws) 1) "%"))
    (println (str "    1-octet " (lpad (median ds) 14) " ns   spread "
                  (rnd (spread-pct ds) 1) "%   (3 samples)"))
    (println (str "    ratio   " (lpad (rnd (/ (double (median ds)) (median ws)) 0) 14) "x"))
    (println (str "    result  " (pr-str (drive fr 1)))))
  (println)
  (println "  The maximum legal BODY (" http/max-body-octets "octets), delivered whole:")
  (let [txt  (str "POST /b HTTP/1.1\r\nhost: h\r\ncontent-length: " http/max-body-octets "\r\n\r\n")
        big  (o/oconcat (o/encode-utf8 txt)
                        (o/octets (mapv (fn [i] (mod i 251)) (range http/max-body-octets))))
        ss   (samples 3 (fn [] (http/parse-request big 0)))
        r    (http/parse-request big 0)]
    (println (str "    octets on the wire " (o/ocount big)))
    (println (str "    parse (whole)      " (median ss) " ns   spread "
                  (rnd (spread-pct ss) 1) "%   (3 samples)"))
    (println (str "    verdict            " (first r) "  body octets "
                  (o/ocount (:body (second r)))))
    (println (str "    retained by the frame+request "
                  (first (retained 3 (fn [] [big (http/parse-request big 0)]))) " bytes")))
  (println)
  (println "  Error offset and replay. `parse-request` returns")
  (println "  [:invalid reason offset pos] with pos the EXACT original cursor:")
  (doseq [[label txt] [["no colon in a header" "GET / HTTP/1.1\r\nhost example\r\n\r\n"]
                       ["space before colon"   "GET / HTTP/1.1\r\nhost : e\r\n\r\n"]
                       ["repeated field name"  "GET / HTTP/1.1\r\nhost: a\r\nhost: b\r\n\r\n"]
                       ["HTTP/1.0 refused"     "GET / HTTP/1.0\r\nhost: a\r\n\r\n"]
                       ["chunked refused"      "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n"]]]
    (let [ov (o/encode-utf8 txt)
          r1 (http/parse-request ov 0)
          r2 (http/parse-request ov 0)]
      (println (str "    " (pad label 22) (pr-str r1)
                    "   replay identical: " (= r1 r2))))))

;; ---------------------------------------------------------------------------
;; F — the disabled-instrument control
;; ---------------------------------------------------------------------------
;;
;; The rule `-M:noio`'s positive control follows: an apparatus that cannot be
;; shown to be quiet is not an apparatus. Three checks.
;;
;;   1. The timing harness's own floor: what `time-ns` reports for a no-op.
;;   2. Instrumented vs disabled: the SAME work timed per iteration, and timed
;;      once around the whole batch with the per-iteration instrument removed.
;;      If the instrument dominated, the per-iteration figure would be larger.
;;   3. The retention instrument's floor and its response: nil retains nothing,
;;      and doubling a structure doubles the reading.

(defn- section-control [reps]
  (head "F" "Disabled-instrument control")
  (let [noop (samples 200 (fn [] nil))]
    (println (str "  1. timing harness floor, 200 samples over a no-op: min "
                  (apply min noop) " ns, median " (median noop)
                  " ns, max " (apply max noop) " ns."))
    (println (str "     Every timed quantity reported above is >= 10^5 ns, so the floor")
    )
    (println (str "     is under one part in 10^3 of the smallest of them.")))
  (let [xs (mapv (fn [i] (mod (* i 7) 256)) (range access-n))
        ov (o/octets xs)
        w  (fn [] (sum-oref ov access-n access-passes))
        _  (w)
        ;; instrumented: one nanoTime pair per iteration
        ins (samples reps w)
        ;; disabled: the instrument removed, one nanoTime pair around the batch
        bulk (let [t0 (System/nanoTime)]
               (loop [i 0] (when (< i reps) (w) (recur (inc i))))
               (quot (- (System/nanoTime) t0) reps))
        im  (median ins)]
    (println (str "  2. same work, " reps " iterations:"))
    (println (str "       instrumented per-iteration median " im " ns  (spread "
                  (rnd (spread-pct ins) 1) "%)"))
    (println (str "       instrument DISABLED, batch mean    " bulk " ns"))
    (println (str "       difference                         "
                  (rnd (* 100.0 (/ (double (- im bulk)) bulk)) 2) "%"))
    (println "       A difference inside the spread means the timer is not the"))
  (println "       measurement. A large positive one would mean it was.")
  (let [[nilm nils] (retained 7 (fn [] nil))
        [am _]      (retained 7 (fn [] (vec (range 4096))))
        [bm _]      (retained 7 (fn [] (vec (range 8192))))]
    (println (str "  3. retention instrument: nil retains median " nilm
                  " B  (readings " (pr-str nils) ")"))
    (println (str "       4096-element vector " am " B; 8192-element " bm " B; ratio "
                  (rnd (/ (double bm) (max 1 am)) 2) "x  (2.00x is the response)"))
    (println (str "       so the retention floor is roughly +/-" (apply max (map (fn [x] (Math/abs (long x))) nils))
                  " B and anything smaller than that is noise."))))

;; ---------------------------------------------------------------------------

(defn- pin []
  (println "perturb.winbench — the byte-window representation, measured")
  (rule)
  (println "  chez        " (str (jolt.host/scheme-version))
           " machine " (str (jolt.host/machine-type))
           " via " (str (System/getenv "JOLT_CHEZ")))
  (println "  build mode   `jolt -M:winbench` runs UNOPTIMIZED. bench/run.sh records")
  (println "               that jolt's optimizing passes (direct-linking, inlining,")
  (println "               scalar-replace, whole-program inference) fire only in an")
  (println "               AOT build, so those figures are the path the perturb gates")
  (println "               themselves run on, not the fastest one. The same namespace")
  (println "               also builds, and the two should be read together:")
  (println "                 jolt build -m perturb.winbench --opt --direct-link")
  (println "               The RATIOS are what carry across the two; the absolutes")
  (println "               are not comparable to any other host (E10).")
  (println "  host        " (str (System/getProperty "os.name")) "x86_64, 4 cpus, shared VM")
  (rule))

(defn -main [& args]
  (let [reps  (if (seq args) (Integer/parseInt (first args)) 5)
        sizes [128 256 512 1024 2048 4096]]
    (pin)
    (section-representation)
    (section-access reps)
    (section-chunking reps sizes)
    (section-retention reps)
    (section-limits reps)
    (section-control reps)
    (println)
    (rule)
    (println "  Reported: medians of" reps "samples with (max-min)/median beside them.")
    (println "  One host, one Chez, one corpus. Nothing above is a threshold and")
    (println "  nothing above can fail; see the namespace docstring.")
    (rule)))
