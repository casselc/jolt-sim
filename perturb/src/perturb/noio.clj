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

  THE SECOND HALF, ADDED AFTER E26 FINDING 3. Everything above measures. A
  measurement is attribution by instrument — E16 nonclaim 2 — and a sixth I/O
  path would be invisible to it. `perturb.effect` now also FAILS CLOSED at the
  boundary and LATCHES the failure in run state, so this namespace reports a
  per-run INVARIANT beside the measurement: `perturb.effect/report` over a
  required-symbol set, printed for every mode and used as this program's exit
  status. `--unhandled-native` is that mechanism's positive control: the same
  `socket(2)` as `--touch-native`, written outside any handler, with the
  exception CAUGHT by the caller. It must be refused before the syscall (so its
  strace window is as clean as run 1's) and the run must still report
  `all-handled? false`. `dev/verify-noio.sh` asserts both.

  The invariant is over the crossings that HAPPENED on this run. It is not a
  proof, and it says nothing about a run nobody performed.

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
  "POSITIVE CONTROL for the INSTRUMENT (E16). One real `:connect` through the
  posix handler, to a port chosen to be refused. Enough to force
  `ensure-native!` and a `socket(2)`."
  []
  (try (fx/with-handlers {'perturb.wire/socket (posix/handler)}
         (w/connect "127.0.0.1" 9 :perturb.noio/control))
       (catch :default e [:aborted (str (:perturb.effect/abort (ex-data e)))])))

;; --- the per-run invariant (E26 finding 3) ----------------------------------
;;
;; The required sets. Their job is to stop a run passing VACUOUSLY: a run that
;; performed no effect at all latches nothing, and without these it would report
;; `:all-handled? true` while proving nothing. Every symbol here is one this run
;; must actually reach.

(def ^:private scripted-required
  "The four socket ops a complete scripted session crosses the boundary with."
  #{'perturb.wire/socket.connect 'perturb.wire/socket.send
    'perturb.wire/socket.recv    'perturb.wire/socket.close})

(def ^:private control-required
  "Additionally, under `--touch-native`: the three C entry points one refused
  connect resolves, each of which had to pass `perturb.posix/gate!`."
  #{'perturb.posix/socket 'perturb.posix/connect 'perturb.posix/close})

;; --- the report -------------------------------------------------------------

(defn -main [& args]
  (cap/reset-ledger!)
  (posix/reset-native-log!)
  (let [control? (some (fn [a] (= a "--touch-native")) args)
        loud?    (some (fn [a] (= a "--print-inside")) args)
        ;; POSITIVE CONTROL for the LATCH. A native crossing written outside any
        ;; handler, whose exception the caller swallows.
        bypass?  (some (fn [a] (= a "--unhandled-native")) args)
        run      (fx/new-run (if control?
                               (into scripted-required control-required)
                               scripted-required))]

    (println begin-marker)
    (flush)

    ;; ---- measured window begins ----
    (let [w (fx/with-run run
              (let [scripted (scripted-run)
                    ctl      (when control? (touch-native))
                    ;; The bypass runs INSIDE the window on purpose. Its window
                    ;; must contain no socket(2) even though a socket(2) was
                    ;; written: fail closed means refused before the crossing,
                    ;; not observed after it.
                    byp      (when bypass? (posix/unhandled-native-probe))]
                ;; LEAK 2 (INHERITED I12), made measurable. With --print-inside the
                ;; scripted results are printed INSIDE the marked window, so the
                ;; strace window shows exactly the write(1, ...) calls that `println`
                ;; performs and nothing else. That is the whole of the console leak,
                ;; counted rather than asserted.
                (when loud?
                  (doseq [v (:values scripted)] (println (str "  => " (pr-str v))))
                  (flush))
                {:scripted scripted :ctl ctl :byp byp}))
          ;; ---- measured window ends ----
          scripted (:scripted w)
          ctl      (:ctl w)
          byp      (:byp w)
          rep      (fx/report run)]

      (println end-marker)
      (flush)

      (println)
      (println (str "mode: " (cond bypass?  "LATCH POSITIVE CONTROL (--unhandled-native)"
                                   control? "POSITIVE CONTROL (--touch-native)"
                                   loud?    "LEAK 2 EXHIBIT (--print-inside)"
                                   :else    "scripted only")))
      (println)
      (println "  namespaces loaded, including perturb.posix and perturb.demo")
      (println (str "  scripted session id   " (pr-str (:session scripted))))
      (println (str "  scripted values       " (pr-str (:values scripted))))
      (println (str "  effect ops performed  " (pr-str (:ops scripted))))
      (when control?
        (println (str "  positive control      " (pr-str ctl))))
      (when bypass?
        (println (str "  latch control         " (pr-str byp)
                      "   <- the caller CAUGHT this and returned normally")))
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
      ;; --- the per-run invariant, printed as data --------------------------
      ;;
      ;; E16 reported a MEASUREMENT of a window. This reports an INVARIANT of a
      ;; run: every effect and every native crossing that happened found a
      ;; registered handler, no boundary fault was latched, and the run reached
      ;; every symbol it was required to reach. It is not a claim about runs
      ;; that did not happen.
      (println "  per-run boundary invariant (perturb.effect/report):")
      (println (str "    all-handled?   " (pr-str (:perturb.effect/all-handled? rep))))
      (println (str "    performs       " (pr-str (:perturb.effect/performs rep))))
      (println (str "    native gates   " (pr-str (:perturb.effect/natives rep))))
      (println (str "    required       " (pr-str (sort (:perturb.effect/required rep)))))
      (println (str "    missing        " (pr-str (sort (:perturb.effect/missing rep)))))
      (println (str "    latched faults " (pr-str (mapv :perturb.effect/abort
                                                        (:perturb.effect/faults rep)))))
      (println (str "    orphan faults  " (pr-str (:perturb.effect/orphan-faults rep))))
      (when (seq (:perturb.effect/faults rep))
        (doseq [f (:perturb.effect/faults rep)]
          (println (str "      " (pr-str f)))))
      ;; The anti-vacuity half, demonstrated rather than asserted. A run that
      ;; performed nothing at all latches nothing, so `:all-handled?` would be
      ;; true on faults alone; the required set is what stops that, and here is
      ;; a report over a run that did nothing, showing it stops it.
      (let [vac (fx/report (fx/new-run scripted-required))]
        (println (str "    vacuous-run check  all-handled? "
                      (pr-str (:perturb.effect/all-handled? vac))
                      "  (a run with no effects: "
                      (count (:perturb.effect/missing vac)) " of "
                      (count scripted-required) " required symbols missing)")))
      (println)
      (let [n  (:calls (posix/native-log-snapshot))
            l  (:library-loads (posix/native-log-snapshot))
            ok (:perturb.effect/all-handled? rep)
            latched (mapv :perturb.effect/abort (:perturb.effect/faults rep))
            ;; The latch control PASSES by failing: it must have tripped the
            ;; latch, and it must NOT have reached native code.
            byp-ok  (and (= [:refused :unhandled-native-effect] byp)
                         (not ok)
                         (= [:unhandled-native-effect] latched)
                         (zero? n) (zero? l))]
        (println (str "  VERDICT (in-process): "
                      (cond
                        bypass? (if byp-ok
                                  "LATCH FIRED — an unhandled native crossing was refused before the syscall, and the run is unsalvageable although the caller caught the throw"
                                  "LATCH DID NOT FIRE AS SPECIFIED — the boundary is blind, ignore every clean run")
                        (and control? (pos? n)) "control fired — the instrument is live"
                        control?                "CONTROL DID NOT FIRE — instrument is dead, ignore the clean run"
                        (and (zero? n) (zero? l)) "scripted run loaded no library and resolved no symbol"
                        :else "LEAK — a scripted run reached native code")))
        (when (and (not bypass?) (not ok))
          (println "  INVARIANT VIOLATED — this run is not evidence of anything."))
        (flush)
        ;; Exit status, so this is a GATE and not a report. `--unhandled-native`
        ;; passes iff the latch tripped; every other mode passes iff it did not.
        (System/exit (if (if bypass? byp-ok ok) 0 1))))))
