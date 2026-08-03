(ns perturb.demo
  "The claims demo. Runs `perturb.nrepl/session` — one var — under a real POSIX
  socket and then under two in-memory handlers, and prints the evidence for each
  of the three claims under test.

  Usage:
    jolt -M:demo [port]        connect to a jolt nREPL server (default: read
                               .nrepl-port, else 7888)
    jolt -M:demo --offline     skip the socket entirely"
  (:require [clojure.string :as str]
            [perturb.octet :as o]
            [perturb.bencode :as b]
            [perturb.effect :as fx]
            [perturb.wire :as w]
            [perturb.nrepl :as pn]
            [perturb.cap :as cap]
            [perturb.posix :as posix]
            [perturb.script :as script]))

(defn- rule [s]
  (println)
  (println (str "=== " s " " (apply str (repeat (max 0 (- 68 (count s))) "=")))))

(defn- show-octets [label ov]
  (let [n (o/ocount ov)
        shown (if (> n 96) (o/osub ov 0 96) ov)]
    (println (str "  " label " (" n " octets)"))
    (println (str "    hex   " (o/hex shown) (if (> n 96) " ..." "")))
    (println (str "    ascii " (o/printable shown) (if (> n 96) " ..." "")))))

(def forms
  ["(+ 1 2)"
   "(clojure.string/upper-case \"perturb\")"
   "(str \"lambda is \" \\u03bb)"])

;; --- claim 1 ----------------------------------------------------------------

(defn claim-1 [recorded]
  (rule "CLAIM 1 — wire bytes are unsigned octets, 0..255")
  (println "  PERTURB-DESIGN §1.5. No unchecked-byte fold anywhere on the path.")
  (println)
  (let [recvs (mapv :octets (filter (fn [e] (= :recv (:op e))) recorded))
        all   (reduce o/oconcat o/empty-octets recvs)
        highs (filter (fn [i] (> (o/oref all i) 0x7f)) (range (o/ocount all)))]
    (if (empty? recvs)
      (println "  (no socket transcript — run with a server to see live wire octets)")
      (do
        (println (str "  received " (o/ocount all) " octets across " (count recvs) " recv() calls"))
        (println (str "  octets above 0x7f on the wire: " (count highs)))
        (if (empty? highs)
          (println "  NOTE: no high octet appeared, so this run does not exhibit the divergence.")
          (let [i  (first highs)
                lo (max 0 (- i 4))
                hi (min (o/ocount all) (+ i 4))
                win (o/osub all lo hi)]
            (println (str "  first at offset " i ", window [" lo "," hi "):"))
            (println (str "    perturb   oref -> " (pr-str (mapv (fn [k] (o/oref win k)) (range (o/ocount win))))))
            (println (str "    jolt      aget over (byte-array ...) -> "
                          (pr-str (o/via-jolt-byte-array win))))
            (println "    the two differ above 0x7f. perturb never built the byte array.")))))
    (println)
    (println "  what perturb.posix actually does:")
    (println "    send: (ffi/write p :uint8 i (o/oref ov i))   -> foreign-set! 'unsigned-8")
    (println "    recv: (ffi/read  p :uint8 i)                 -> foreign-ref  'unsigned-8")
    (println "  what jolt.nrepl does:")
    (println "    send: (byte-array (map int s)) then write-array  -> na-byte-of narrows on STORE")
    (println "    recv: (String. (ffi/read-array buf n) \"ISO-8859-1\")")
    (println)
    (println "  measured on this baseline:")
    (println (str "    (o/oref (o/octets [200 255 128]) i) = "
                  (pr-str (let [ov (o/octets [200 255 128])] (mapv (fn [i] (o/oref ov i)) (range 3))))))
    (println (str "    (aget (byte-array [200 255 128]) i)  = "
                  (pr-str (o/via-jolt-byte-array (o/octets [200 255 128])))))
    (println "    -> jolt byte arrays store signed (natives-array.ss na-byte-of),")
    (println "       so this is not an accessor perturb can swap out. See SHAREABLE.md.")))

;; --- claim 2 ----------------------------------------------------------------

(defn- run-session [handler-name handler host port]
  (let [trace (atom [])]
    (let [result (fx/with-trace trace
                   (fx/with-handlers {'perturb.wire/socket handler}
                     (pn/session host port forms)))]
      {:handler handler-name :result result :trace @trace})))

(defn claim-2 [runs native-marks]
  (rule "CLAIM 2 — I/O goes through a declared effect, same code, two handlers")
  (println "  PERTURB-DESIGN §1.4. Handlers substitute a validated result or abort.")
  (println)
  (println "  the effect, as declared data:")
  (doseq [p (sort-by (fn [x] (str (first x))) (seq (fx/ops w/socket)))]
    (println (str "    " (first p) "  arity " (:arity (second p)))))
  (println)
  (println "  one var ran under every handler below:")
  (println (str "    " (pr-str (var pn/session))))
  (println)
  (doseq [r runs]
    (let [res (:result r)]
      (println (str "  handler " (:handler r)))
      (println (str "    session     " (pr-str (:session res))))
      (println (str "    values      " (pr-str (mapv :value (:results res)))))
      (println (str "    effect ops  " (pr-str (frequencies (map :perturb.effect/op (:trace r))))))
      (println (str "    perform sites " (pr-str (vec (distinct (map :perturb.effect/site (:trace r)))))))))
  (println)
  (println "  the handlers are NOT interchangeable in what they do:")
  (doseq [r runs]
    (println (str "    " (:handler r) " -> recv calls: "
                  (count (filter (fn [e] (= :recv (:perturb.effect/op e))) (:trace r))))))
  (println "  the scripted handlers deliver one octet per recv, so the same driver")
  (println "  crosses the :need-more arm hundreds of times where the socket crossed it")
  (println "  a handful. E4's exact-original-cursor contract is what makes that safe.")
  (println)
  (println "  handler-result validation (the `validated` in §1.4):")
  (let [liar (fn [op site args]
               (if (= op :recv) [:ok "not an octet view"] [:ok (cond (= op :connect) [:liar]
                                                                     (= op :send) 0
                                                                     :else :closed)]))]
    (println (str "    a handler returning a string from :recv -> "
                  (try (fx/with-handlers {'perturb.wire/socket liar}
                         (w/recv [:liar] 10 :perturb.demo/probe))
                       "NOT REJECTED"
                       (catch :default e (str (:perturb.effect/abort (ex-data e))))))))
  (println (str "    an unhandled effect -> "
                (try (binding [fx/*handlers* {}] (w/recv [:x] 10 :perturb.demo/probe))
                     "NOT REJECTED"
                     (catch :default e (str (:perturb.effect/abort (ex-data e)))))))
  (println)
  (println "  I11 IS CLOSED — the native binding is lazy and handler-local:")
  (println "    perturb.posix no longer calls load-library at namespace load. It")
  (println "    loads no library and resolves no C symbol until a `sys-*` wrapper")
  (println "    runs, and those are reached only from the handler, which is reached")
  (println "    only from perturb.effect/perform. Measured on THIS run —")
  (println "    perturb.posix/native-log sampled at each stage:")
  (doseq [m native-marks]
    (println (str "      " (first m) (apply str (repeat (max 1 (- 22 (count (str (first m))))) " "))
                  (pr-str (second m)))))
  (println "    a scripted run adds nothing to that log; only the socket run does.")
  (println)
  (println "    that `def` resolves no C entry point is not an assumption — the")
  (println "    absent-symbol canary is bound by `defcfn` at namespace load and")
  (println "    names a symbol that exists in no object in this process:")
  (println (str "      requiring perturb.posix succeeded (this program is running)"))
  (println (str "      (perturb.posix/absent-canary-probe) -> " (pr-str (posix/absent-canary-probe))))
  (println "    -> resolution happens at CALL. Zero native calls is zero symbols.")
  (println)
  (println "    process-level check: `jolt -M:noio` runs a complete scripted session")
  (println "    between two marker writes; `dev/verify-noio.sh` straces it and shows")
  (println "    zero syscalls attributable to perturb in that window, with a positive")
  (println "    control (--touch-native) that does fire. See INHERITED I11.")
  (println)
  (println "  I12 IS STILL OPEN, AND NOW MEASURED — console output is unmediated:")
  (println "    every line of this transcript is a write(2) outside any handler.")
  (println "    dev/verify-noio.sh RUN 3 counts them exactly. Left unrouted on")
  (println "    purpose: an effect does not remove I/O, it makes I/O SUBSTITUTABLE.")
  (println "    The socket effect earns that because RUN B and RUN C are a second")
  (println "    and third implementation of the same interface running the same var.")
  (println "    Nothing in perturb consumes perturb's console output, so a console")
  (println "    handler would have no second implementation to be checked against —")
  (println "    it would move the write(2) behind a name without adding a fact.")
  (println "    Recorded, not dropped: INHERITED I12."))

;; --- claim 3 ----------------------------------------------------------------

(defn claim-3 []
  (rule "CLAIM 3 — capability discipline, hand-annotated, NOT checked")
  (println "  PERTURB-DESIGN §1.2. Annotations are data; no checker exists or is built.")
  (println)
  (let [ci (cap/checker-input)]
    (println "  declared capabilities:")
    (doseq [p (seq (:perturb.cap/declarations ci))]
      (let [d (second p)]
        (println (str "    " (first p)))
        (println (str "      uniqueness " (:perturb.cap/uniqueness d)
                      "  linearity " (:perturb.cap/linearity d)
                      "  contention " (:perturb.cap/contention d)))
        (println (str "      typestate  " (pr-str (:states (:perturb.cap/typestate d)))
                      " initial " (:initial (:perturb.cap/typestate d))
                      " terminal " (:terminal (:perturb.cap/typestate d))))))
    (println)
    (println "  operation annotations (also on each var's metadata):")
    (doseq [p (seq (:perturb.cap/operations ci))]
      (println (str "    " (first p) "  " (pr-str (second p)))))
    (println)
    (println "  observed ledger, per capability instance:")
    (doseq [p (sort-by (fn [x] (str (first x))) (seq (cap/ledger-summary)))]
      (let [states (second p)]
        (when (str/starts-with? (str (first p)) "perturb-conn")
          (println (str "    " (first p) "  " (pr-str states))))))
    (let [conns (filter (fn [p] (str/starts-with? (str (first p)) "perturb-conn"))
                        (seq (cap/ledger-summary)))
          closes (fn [states] (count (filter (fn [s] (= :closed s)) states)))]
      (println)
      (println (str "    connections opened: " (count conns)))
      (println (str "    reached :closed exactly once each: "
                    (pr-str (mapv (fn [p] (closes (second p))) conns))))
      (println (str "    native buffers alloc/free pairs: "
                    (count (filter (fn [e] (= :freed (:perturb.cap/to e)))
                                   (:perturb.cap/ledger ci)))
                    " freed / "
                    (count (filter (fn [e] (= :allocated (:perturb.cap/to e)))
                                   (:perturb.cap/ledger ci)))
                    " allocated")))
    (println)
    (println "  READ THIS AS EVIDENCE OF ITS ACTUAL STRENGTH: the ledger is an")
    (println "  observation of one run, and the annotations are hand-written. Nothing")
    (println "  above rejects anything — perturb.cap/note! would happily record a")
    (println "  transition the declared machine forbids. The affine threading in")
    (println "  perturb.nrepl (each op consumes the connection and returns its")
    (println "  successor) is what makes use-after-close hard to write; it is not")
    (println "  what makes it impossible.")
    (println)
    (println "  checker-input keys emitted for a future checker: "
             (pr-str (vec (keys ci))))))

;; --- the transcript ---------------------------------------------------------

(defn print-transcript [recorded limit]
  (rule "WIRE TRANSCRIPT (real socket)")
  (doseq [e (clojure.core/take limit recorded)]
    (cond
      (= :connect (:op e)) (println (str "  connect 127.0.0.1:" (:port e)))
      (= :close (:op e))   (println "  close")
      :else (show-octets (str (name (:op e)) "  site=" (:site e)) (:octets e)))))

;; --- main -------------------------------------------------------------------

(defn- read-port [args]
  (let [a (first args)]
    (cond
      (and a (re-matches #"\d+" a)) (parse-long a)
      :else (try (parse-long (str/trim (slurp ".nrepl-port")))
                 (catch :default _ 7888)))))

(defn -main [& args]
  (cap/reset-ledger!)
  (posix/reset-native-log!)
  (let [offline (some (fn [a] (= a "--offline")) args)
        port    (read-port (remove (fn [a] (str/starts-with? a "--")) args))
        recorded (atom [])
        runs (atom [])
        ;; I11's evidence: sample the instrumented native-call log at each stage.
        ;; The scripted stages must not move it.
        native-marks (atom [["at startup" (posix/native-log-snapshot)]])
        mark! (fn [label] (swap! native-marks conj [label (posix/native-log-snapshot)]))]

    (println "perturb — nREPL client over a declared socket effect")
    (println (str "forms to evaluate: " (pr-str forms)))

    ;; (a) real socket
    (when-not offline
      (rule "RUN A — real POSIX socket")
      (println (str "  connecting to 127.0.0.1:" port))
      (let [r (try (run-session "posix (real socket)" (posix/handler recorded) "127.0.0.1" port)
                   (catch :default e
                     (println (str "  FAILED: " (ex-message e)))
                     (println (str "  data:   " (pr-str (ex-data e))))
                     nil))]
        (when r
          (swap! runs conj r)
          (doseq [x (:results (:result r))]
            (println (str "  => " (pr-str (:value x))
                          (when (:out x) (str "   out=" (pr-str (:out x))))
                          (when (:err x) (str "   err=" (pr-str (:err x))))))))))
    (mark! "after RUN A")

    ;; (b) scripted model server, 1 octet per recv
    (rule "RUN B — scripted in-memory handler (1 octet per recv)")
    (let [r (run-session "script (model server)" (script/model-handler {:chunk-size 1}) "in-memory" 0)]
      (swap! runs conj r)
      (doseq [x (:results (:result r))]
        (println (str "  => " (pr-str (:value x))))))
    (mark! "after RUN B")

    ;; (c) replay of run A's octets, rechunked
    (when (seq @recorded)
      (rule "RUN C — replay of RUN A's recorded octets, rechunked to 1 per recv")
      (let [crec (atom [])
            r (run-session "script (replay of A)" (script/replay-handler @recorded 1 crec) "replay" 0)]
        (swap! runs conj r)
        (doseq [x (:results (:result r))]
          (println (str "  => " (pr-str (:value x)))))
        (let [a (first (filter (fn [x] (= "posix (real socket)" (:handler x))) @runs))
              sends-a (mapv (fn [e] (o/ovec (:octets e)))
                            (filter (fn [e] (= :send (:op e))) @recorded))
              sends-c (mapv (fn [e] (o/ovec (:octets e)))
                            (filter (fn [e] (= :send (:op e))) @crec))]
          (println)
          (println (str "  values identical to RUN A: "
                        (= (mapv :value (:results (:result a)))
                           (mapv :value (:results (:result r))))))
          (println (str "  bytes SENT identical to RUN A, octet for octet: "
                        (= sends-a sends-c)
                        "  (" (count sends-a) " frames, "
                        (reduce + 0 (map count sends-a)) " octets)"))
          (println "  -> the same encoder produced the same wire bytes under a")
          (println "     handler that is not a socket, and the same decoder read")
          (println "     them back one octet at a time."))))
    (mark! "after RUN C")

    (when (seq @recorded) (print-transcript @recorded 12))

    (claim-1 @recorded)
    (claim-2 @runs @native-marks)
    (claim-3)

    (rule "END")
    (println "  logs: perturb/docs/SHAREABLE.md, perturb/docs/INHERITED.md")))
