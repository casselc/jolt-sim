(ns perturb.noio
  "The verifier for CLAIM 2's first leak (INHERITED I11): a scripted run performs
  no I/O.

  This namespace exists to be *measured from outside*, so its structure is
  unusual and deliberate:

    1. It `:require`s `perturb.posix`. That is the namespace under indictment.
       Requiring it must be quiet.
    2. `-main` writes one marker line, runs a complete scripted nREPL session
       printing NOTHING, then writes a second marker line. Everything between
       the two markers is the run under test. `dev/verify-noio.sh` straces the
       process and asserts that window contains zero syscalls.
    3. Only after the closing marker does it report. The report is buffered
       data, not something the measured window produced.

  A negative measurement with no positive control is not evidence, so
  `--touch-native` runs the same shape with ONE real `:connect` through
  `perturb.posix/handler` inside the window. The window then contains
  `socket`/`connect`/`close`, and `perturb.posix/native-log` reads non-zero. If
  the control does not fire, the clean run means nothing.

  WHAT strace CAN AND CANNOT SEE, measured on this host:

    (jolt.ffi/load-library)              -> zero syscalls
    (jolt.ffi/load-library \"libz.so.1\")  -> openat + 4 mmaps + mprotect

  The no-argument form is `(load-shared-object #f)`, i.e. `dlopen(NULL)`, which
  binds the process's own already-mapped symbols and touches no file. So I11's
  load-time leak was real as a control-flow fact and INVISIBLE as a syscall
  fact. That is why the counter in `perturb.posix/native-log` is here: for this
  particular leak an instrumented `load-library` is the sensitive instrument and
  strace is not. strace covers the other half — that the scripted run makes no
  socket call, opens no file, maps no object, and in fact makes no syscall at
  all.

  Symbol resolution is likewise invisible to strace (`dlsym` does not syscall).
  It is covered by `perturb.posix/c-absent-canary`: a `defcfn` on a symbol that
  exists in no object in this process, evaluated at namespace load. This
  namespace loading at all is the evidence that `def` resolves nothing; the
  probe below is the evidence that resolution happens at call."
  (:require [perturb.effect :as fx]
            [perturb.wire :as w]
            [perturb.nrepl :as pn]
            [perturb.cap :as cap]
            [perturb.demo :as demo]
            [perturb.posix :as posix]
            [perturb.script :as script]))

(def ^:private begin-marker "PERTURB-NOIO-BEGIN")
(def ^:private end-marker   "PERTURB-NOIO-END")

;; --- the measured window ----------------------------------------------------
;; Everything in here returns data. Nothing in here prints, allocates a file,
;; or names perturb.posix's handler.

(defn- scripted-run
  "A full nREPL session — clone, three evals, close — under the in-memory model
  handler at one octet per recv. Same `perturb.nrepl/session` var the demo runs
  against a real socket."
  []
  (let [trace (atom [])
        res   (fx/with-trace trace
                (fx/with-handlers {'perturb.wire/socket (script/model-handler {:chunk-size 1})}
                  (pn/session "in-memory" 0 demo/forms)))]
    {:session (:session res)
     :values  (mapv :value (:results res))
     :ops     (frequencies (map :perturb.effect/op @trace))}))

(defn- touch-native
  "POSITIVE CONTROL. One real `:connect` through the posix handler, to a port
  chosen to be refused. Enough to force `ensure-native!` and a `socket(2)`."
  []
  (try (fx/with-handlers {'perturb.wire/socket (posix/handler)}
         (w/connect "127.0.0.1" 9 :perturb.noio/control))
       (catch :default e [:aborted (str (:perturb.effect/abort (ex-data e)))])))

;; --- the report -------------------------------------------------------------

(defn -main [& args]
  (cap/reset-ledger!)
  (posix/reset-native-log!)
  (let [control? (some (fn [a] (= a "--touch-native")) args)
        loud?    (some (fn [a] (= a "--print-inside")) args)]

    (println begin-marker)
    (flush)

    ;; ---- measured window begins ----
    (let [scripted (scripted-run)
          ctl      (when control? (touch-native))
          ;; LEAK 2 (INHERITED I12), made measurable. With --print-inside the
          ;; scripted results are printed INSIDE the marked window, so the
          ;; strace window shows exactly the write(1, ...) calls that `println`
          ;; performs and nothing else. That is the whole of the console leak,
          ;; counted rather than asserted.
          _        (when loud?
                     (doseq [v (:values scripted)] (println (str "  => " (pr-str v))))
                     (flush))]
      ;; ---- measured window ends ----

      (println end-marker)
      (flush)

      (println)
      (println (str "mode: " (cond control? "POSITIVE CONTROL (--touch-native)"
                                   loud?    "LEAK 2 EXHIBIT (--print-inside)"
                                   :else    "scripted only")))
      (println)
      (println "  namespaces loaded, including perturb.posix and perturb.demo")
      (println (str "  scripted session id   " (pr-str (:session scripted))))
      (println (str "  scripted values       " (pr-str (:values scripted))))
      (println (str "  effect ops performed  " (pr-str (:ops scripted))))
      (when control?
        (println (str "  positive control      " (pr-str ctl))))
      (println)
      (println "  perturb.posix/native-log — the instrumented load-library and the")
      (println "  five syscall bindings, counted at the only place that reaches them:")
      (println (str "    " (pr-str (posix/native-log-snapshot))))
      (println)
      (println "  absent-symbol canary (perturb.posix/c-absent-canary):")
      (println "    this namespace required perturb.posix and loaded -> a defcfn `def`")
      (println "    resolved no entry point, or that require would have failed.")
      (println (str "    calling it now -> " (pr-str (posix/absent-canary-probe))))
      (println "    -> resolution happens at CALL, so the `:calls` count above is also")
      (println "       the count of C symbols this process resolved from perturb.posix.")
      (println)
      (let [n (:calls (posix/native-log-snapshot))
            l (:library-loads (posix/native-log-snapshot))]
        (println (str "  VERDICT (in-process): "
                      (cond
                        (and control? (pos? n)) "control fired — the instrument is live"
                        control?                "CONTROL DID NOT FIRE — instrument is dead, ignore the clean run"
                        (and (zero? n) (zero? l)) "scripted run loaded no library and resolved no symbol"
                        :else "LEAK — a scripted run reached native code")))))))
