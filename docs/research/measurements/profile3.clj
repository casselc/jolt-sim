(ns profile3
  (:require [jolt.bytes :as bytes]))

;; Does the devirt path work at all for a deftype receiver, and is `nth` slow
;; only because it is a core builtin rather than a protocol method?
;;
;; P/pget is a USER protocol method -> passes/types.clj can attach :devirt-type
;; (it resolves through env's :protocol-methods).
;; nth is clojure.core -> never resolves there, so never devirtualizes.
;;
;; Same deftype, same namespace, same loop. If pget is much faster than nth,
;; the devirt machinery works and the only missing piece is recognizing
;; collection interfaces. If both are slow, inference is not reaching the
;; receiver and the fix is larger.

(defprotocol P (pget [this i]))

(deftype W [b]
  P
  (pget [this i] 7)
  clojure.lang.Indexed
  (nth [this i] 7)
  clojure.lang.Counted
  (count [this] 4)
  clojure.lang.Seqable
  (seq [this] nil)
  clojure.lang.IReduce
  (reduce [this f] 0)
  clojure.lang.IReduceInit
  (reduce [this f init] init))

(def w (->W 1))
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
  ;; user protocol method on the same deftype
  (timed :P-pget-user-protocol 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (long (pget w i)))))))
  ;; core collection builtin on the same deftype
  (timed :N-nth-core-builtin 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (long (nth w i)))))))
  ;; direct method call, the floor for this receiver
  (timed :D-dot-field-form 5000
         #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (long (.b w)))))))
  ;; same dispatch, REAL body: jolt.bytes/Window's nth does bounds + offset + aget
  (let [win (bytes/window (.getBytes "(reduce + (range 100))" "UTF-8"))]
    (timed :W-nth-real-window 5000
           #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (long (nth win i))))))))
  (let [v (vec (range n))]
    (timed :V-nth-persistent-vector 5000
           #(loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (long (nth v i))))))))) 
