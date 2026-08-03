(ns profile
  (:require [jolt.bencode :as bencode]
            [jolt.bytes :as bytes]))

(def message
  {"code" "(reduce + (range 100))"
   "id" "benchmark-0001"
   "op" "eval"
   "session" "jolt-42"
   "status" ["done"]})

(def wire (bencode/encode message))
(def win (bytes/window wire))

;; A representative 22-byte payload, the largest string in the frame.
(def sample-bytes (.getBytes "(reduce + (range 100))" "UTF-8"))
(def sample-win (bytes/window sample-bytes))
(def sample-len (alength sample-bytes))

(def decode-utf8 @#'jolt.bencode/decode-utf8)
(def window-octets @#'jolt.bencode/window-octets)
(def octet @#'jolt.bencode/octet)

(defn- timed [label n f]
  (dotimes [_ (quot n 4)] (f))                       ; warmup
  (let [start (System/nanoTime)
        cs (loop [i n acc 0] (if (zero? i) acc (recur (dec i) (unchecked-add acc (long (f))))))
        elapsed (- (System/nanoTime) start)]
    (println
     (pr-str {:label label
              :iterations n
              :nanos-per-op (quot elapsed n)
              :checksum cs}))
    (flush)))

;; --- floor: raw byte scan over the Window, no allocation beyond the loop -----
(defn- raw-scan [w len]
  (loop [i 0 sum 0]
    (if (= i len) sum (recur (inc i) (+ sum (octet w i))))))

;; --- a direct single-pass UTF-8 validator, no String round-trip -------------
(defn- direct-utf8-ok? [w len]
  (loop [i 0]
    (if (>= i len)
      1
      (let [b (octet w i)]
        (cond
          (< b 0x80) (recur (inc i))
          (< b 0xC0) 0
          (< b 0xE0) (if (and (< (+ i 1) len) (= 0x80 (bit-and (octet w (inc i)) 0xC0)))
                       (recur (+ i 2)) 0)
          (< b 0xF0) (if (and (< (+ i 2) len)
                              (= 0x80 (bit-and (octet w (+ i 1)) 0xC0))
                              (= 0x80 (bit-and (octet w (+ i 2)) 0xC0)))
                       (recur (+ i 3)) 0)
          :else 0)))))

(defn -main [& _]
  (println (pr-str {:wire-bytes (alength wire) :sample-bytes sample-len}))
  (timed :A-full-decode 2000
         #(bytes/cursor-position (:cursor (bencode/decode-bytes wire))))
  (timed :B-decode-utf8-one-string 2000
         #(count (decode-utf8 sample-win 0 sample-len)))
  (timed :C-window-octets-one-string 2000
         #(count (window-octets sample-win 0 sample-len)))
  (timed :D-string-roundtrip-only 2000
         #(alength (.getBytes (String. sample-bytes "UTF-8") "UTF-8")))
  (timed :E-raw-scan-one-string 2000
         #(raw-scan sample-win sample-len))
  (timed :F-direct-utf8-validate 2000
         #(direct-utf8-ok? sample-win sample-len)))
