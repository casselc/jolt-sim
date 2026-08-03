(ns profile4)

;; E7 put 845 of Window/nth's 1145 ns/byte inside the METHOD BODY, ~15x a bare
;; aget. This decomposes that body. jolt.bytes/Window's nth is:
;;
;;   (let [index (int index)]                    ; checked cast, jolt-int-cast
;;     (if (valid-index? length index)           ; integer? + <= + <  (generic)
;;       (signed-byte-at backing (+ offset index)) ; generic + , aget, unchecked-byte
;;       (throw ...)))
;;
;; Each stage below is the same code standalone, so the cost lands on the
;; operation rather than on deftype dispatch.

(def ^:private backing (.getBytes "(reduce + (range 100))" "UTF-8"))
(def n 22)
(def ^:private offset 0)
(def ^:private length 22)

(defn- signed-byte-at [^bytes b index]
  (unchecked-byte (aget b index)))

(defn- plain-aget [^bytes b index]
  (aget b index))

(defn- hintless-aget [b index]
  (aget b index))

(defn- valid-index? [length index]
  (and (integer? index)
       (<= 0 index)
       (< index length)))

(defn- timed [label iters f]
  (dotimes [_ (quot iters 4)] (f))
  (let [start (System/nanoTime)
        cs (loop [i iters acc 0] (if (zero? i) acc (recur (dec i) (unchecked-add acc (long (f))))))
        elapsed (- (System/nanoTime) start)]
    (println (pr-str {:label label
                      :nanos-per-op (quot elapsed iters)
                      :nanos-per-byte (quot (quot elapsed iters) n)
                      :checksum cs}))
    (flush)))

(defn -main [& _]
  ;; floor: loop overhead only
  (timed :0-loop-only 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s 1)))))
  ;; raw aget, no wrapper
  (timed :1-aget-raw 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (aget backing i))))))
  ;; + unchecked-byte, i.e. signed-byte-at
  (timed :2-signed-byte-at 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (signed-byte-at backing i))))))
  ;; the (int index) checked cast alone
  (timed :3-int-cast 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (int i))))))
  ;; the three generic predicates alone
  (timed :4-valid-index? 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (if (valid-index? length i) 1 0))))))
  ;; integer? alone, the type predicate inside valid-index?
  (timed :5-integer?-only 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (if (integer? i) 1 0))))))
  ;; generic + on the offset, as (+ offset index)
  (timed :6-generic-add 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (+ offset i))))))
  ;; aget through a defn- WITHOUT unchecked-byte -- isolates call overhead
  (timed :8-aget-in-defn-hinted 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (plain-aget backing i))))))
  ;; same, no ^bytes hint -- isolates what the hint is worth
  (timed :9-aget-in-defn-hintless 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (hintless-aget backing i))))))
  ;; unchecked-byte alone on a constant
  (timed :A-unchecked-byte-only 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (unchecked-byte 65))))))
  ;; the WHOLE body, standalone, no deftype dispatch
  (timed :7-full-body-standalone 5000
         #(loop [i 0 s 0]
            (if (= i n) s
                (recur (inc i)
                       (+ s (let [index (int i)]
                              (if (valid-index? length index)
                                (signed-byte-at backing (+ offset index))
                                0))))))))
