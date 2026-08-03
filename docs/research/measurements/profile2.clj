(ns profile2
  (:require [jolt.bytes :as bytes]))

(def sample-bytes (.getBytes "(reduce + (range 100))" "UTF-8"))
(def sample-win (bytes/window sample-bytes))
(def n 22)

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
  ;; aget on a raw byte-array — the floor
  (timed :G-aget-bytearray 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (aget sample-bytes i))))))
  ;; nth on the Window deftype, nothing else
  (timed :H-nth-window 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (long (nth sample-win i)))))))
  ;; the bit-and/long arithmetic alone, no access
  (timed :I-arith-only 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (bit-and (long 200) 0xff))))))
  ;; nth on a plain persistent vector, for dispatch comparison
  (let [v (vec (seq sample-bytes))]
    (timed :J-nth-vector 5000
           #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (long (nth v i))))))))
  ;; reduce over the Window (IReduce path) instead of indexed nth
  (timed :K-reduce-window 5000
         #(reduce (fn [s b] (+ s (long b))) 0 sample-win)))
