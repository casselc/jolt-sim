(ns perturb.selftest
  "Codec and octet self-tests. No socket, no server, no FFI."
  (:require [perturb.octet :as o]
            [perturb.bencode :as b]
            [perturb.http :as h]))

(def ^:private failures (atom []))

(defn- check [name expected actual]
  (if (= expected actual)
    (println (str "  ok   " name))
    (do (swap! failures conj name)
        (println (str "  FAIL " name))
        (println (str "         expected " (pr-str expected)))
        (println (str "         actual   " (pr-str actual))))))

(defn- roundtrip [name v]
  (let [ov (b/encode v)
        r  (b/decode ov 0)]
    (check (str name " -> :ok at exact frame end")
           [:ok (o/ocount ov)]
           [(first r) (nth r 2)])
    (check (str name " roundtrip") v (b/dstrs (second r)))))

(defn- prefix-need-more
  "Every proper prefix of a frame must return :need-more with the EXACT original
  cursor. E4's contract, tested rather than asserted."
  [name v]
  (let [ov (b/encode v)
        n  (o/ocount ov)
        bad (filter (fn [i]
                      (let [r (b/decode (o/osub ov 0 i) 0)]
                        (not= r [:need-more 0])))
                    (range 1 n))]
    (check (str name " — all " (dec n) " proper prefixes are [:need-more 0]") [] (vec bad))))

(defn run []
  (reset! failures [])
  (println "== perturb.octet ==")
  (check "octet? rejects -1" false (o/octet? -1))
  (check "octet? accepts 255" true (o/octet? 255))
  (check "octet? rejects 256" false (o/octet? 256))
  (check "construction rejects a signed byte"
         :rejected
         (try (do (o/octets [-56]) :accepted) (catch :default _ :rejected)))
  (check "oref returns 0..255, no fold"
         [200 255 127 128 0]
         (let [ov (o/octets [200 255 127 128 0])]
           (mapv (fn [i] (o/oref ov i)) (range 5))))
  (check "the same values through a jolt byte array read back signed"
         [-56 -1 127 -128 0]
         (o/via-jolt-byte-array (o/octets [200 255 127 128 0])))
  (check "the fold recovers them — this is the work perturb's path never does"
         [200 255 127 128 0]
         (o/ovec (o/from-signed (o/via-jolt-byte-array (o/octets [200 255 127 128 0])))))

  (println "== perturb.octet UTF-8 ==")
  (check "ascii" [104 105] (o/ovec (o/encode-utf8 "hi")))
  (check "U+03BB lambda" [0xCE 0xBB] (o/ovec (o/encode-utf8 "λ")))
  (check "lambda roundtrips to a host string" "λ" (o/->str (o/encode-utf8 "λ")))
  (check "decoder handles an astral code point (U+1D11E)"
         [:ok [0x1D11E]]
         (o/decode-utf8 (o/octets [0xF0 0x9D 0x84 0x9E])))
  (check "but ->str cannot build the host string for it (INHERITED I2)"
         :I2
         (try (do (o/->str (o/octets [0xF0 0x9D 0x84 0x9E])) :built)
              (catch :default e (:perturb.inherited/entry (ex-data e)))))
  (check "overlong 2-byte form rejected" [:invalid :bad-leading-octet 0]
         (o/decode-utf8 (o/octets [0xC0 0x80])))
  (check "surrogate rejected" [:invalid :surrogate 0]
         (o/decode-utf8 (o/octets [0xED 0xA0 0x80])))
  (check "truncated 3-byte rejected" [:invalid :truncated-3 0]
         (o/decode-utf8 (o/octets [0xE2 0x82])))

  (println "== perturb.bencode roundtrips ==")
  (roundtrip "int" 42)
  (roundtrip "negative int" -17)
  (roundtrip "zero" 0)
  (roundtrip "string" "clone")
  (roundtrip "empty list" [])
  (roundtrip "list" ["a" 1 ["b"]])
  (roundtrip "dict" {"op" "eval" "code" "(+ 1 2)"})
  (roundtrip "nested dict" {"a" {"b" [1 2 3]} "c" "d"})
  (roundtrip "high octets survive as text" {"v" "λÿ"})

  (println "== perturb.bencode trichotomy (E4) ==")
  (prefix-need-more "dict" {"op" "eval" "code" "(+ 1 2)" "id" "7"})
  (prefix-need-more "nested list" [1 ["two" [3]] "four"])
  (check "trailing garbage does not affect the frame"
         [:ok 3]
         (let [ov (o/oconcat (b/encode 1) (o/octets [1 2 3]))
               r  (b/decode ov 0)]
           [(first r) (nth r 2)]))
  (check "invalid leading octet" [:invalid :unexpected-octet 0 0]
         (b/decode (o/octets [0x7A]) 0))
  (check "non-digit in integer" [:invalid :not-a-digit 2 0]
         (b/decode (o/octets [0x69 0x31 0x7A 0x65]) 0))

  (println "== raw byte strings stay octets ==")
  (check "a byte string above 0x7f decodes to octets, not a signed anything"
         [0xFF 0x80 0x00]
         (let [ov (b/encode (o/octets [0xFF 0x80 0x00]))
               r  (b/decode ov 0)]
           (o/ovec (second r))))

  (println "== perturb.http trichotomy, the SECOND protocol to carry it ==")
  (let [raw (str "POST /a?b=c HTTP/1.1\r\n"
                 "Host: perturb\r\n"
                 "Content-Length: 5\r\n"
                 "\r\n"
                 "hello")
        ov  (o/encode-utf8 raw)
        n   (o/ocount ov)
        r   (h/parse-request ov 0)]
    (check "complete request -> :ok at the exact request boundary"
           [:ok n] [(first r) (nth r 2)])
    (check "method/target/version" ["POST" "/a?b=c" "HTTP/1.1"]
           [(:method (second r)) (:target (second r)) (:version (second r))])
    (check "field names lower-cased at the octet level"
           {"host" "perturb" "content-length" "5"} (:headers (second r)))
    (check "body is an OCTET VIEW, not text" [104 101 108 108 111]
           (o/ovec (:body (second r))))
    ;; The property the one-octet-per-recv handler exercises 121 times per demo
    ;; run, tested directly here so a failure names itself.
    (check (str "all " (dec n) " proper prefixes are [:need-more 0]")
           []
           (vec (filter (fn [i] (not= [:need-more 0] (h/parse-request (o/osub ov 0 i) 0)))
                        (range 1 n))))
    ;; Pipelining: the SECOND request must come out of the same buffer at the
    ;; cursor the first one returned, with no I/O in between. This is the
    ;; property `perturb.nrepl` never needed and the keep-alive cycle rests on.
    (let [two (o/oconcat ov ov)
          r1  (h/parse-request two 0)
          r2  (h/parse-request two (nth r1 2))]
      (check "two pipelined requests parse from one buffer at successive cursors"
             [:ok n :ok (* 2 n)]
             [(first r1) (nth r1 2) (first r2) (nth r2 2)])))
  (check "HTTP/1.0 is refused, not downgraded" [:invalid :unsupported-version 0 0]
         (h/parse-request (o/encode-utf8 "GET / HTTP/1.0\r\n\r\n") 0))
  (check "chunked is refused, not mis-parsed" :chunked-unsupported
         (second (h/parse-request
                   (o/encode-utf8 "GET / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n") 0)))
  (check "a repeated field name is an error, not last-wins" :repeated-field-name
         (second (h/parse-request
                   (o/encode-utf8 "GET / HTTP/1.1\r\na: 1\r\na: 2\r\n\r\n") 0)))
  (check "whitespace before the colon is refused (RFC 9112 5.1)" :space-before-colon
         (second (h/parse-request (o/encode-utf8 "GET / HTTP/1.1\r\nhost : x\r\n\r\n") 0)))
  (check "a non-token method is refused" :bad-method
         (second (h/parse-request (o/encode-utf8 "G(T / HTTP/1.1\r\n\r\n") 0)))
  (check "an unterminated head over the limit becomes :invalid, not :need-more"
         :head-too-large
         (second (h/parse-request
                   (o/encode-utf8 (apply str (repeat 9000 "x"))) 0)))
  (check "response-head commits to a length nothing has written yet"
         "HTTP/1.1 200 OK\r\ncontent-length: 6\r\n\r\n"
         (o/->str (h/response-head 200 "OK" {} 6)))

  (println)
  (if (empty? @failures)
    (println "SELFTEST OK")
    (println (str "SELFTEST FAILED: " (pr-str @failures))))
  (empty? @failures))

(defn -main [& _]
  (if (run) (System/exit 0) (System/exit 1)))
