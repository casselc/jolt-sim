(ns perturb.posix
  "Handler (a): a real POSIX TCP socket.

  CLAIM 1's evidence lives in `send-octets!` and `recv-octets`. Both move wire
  bytes with `jolt.ffi`'s `:uint8`, which lowers to Chez `foreign-ref` /
  `foreign-set!` on `'unsigned-8`. Values are 0..255 in both directions and no
  fold happens at either end. Compare `jolt.nrepl`, which does
  `(byte-array (map int s))` on the way out — narrowing every octet above 0x7f
  to a negative slot via `na-byte-of` — and
  `(String. (ffi/read-array buf n) \"ISO-8859-1\")` on the way in.

  INHERITED I6, I7: the FFI type table and the sockaddr layout copied from
  `jolt.nrepl` are Jolt's.

  I11 (load-time `load-library`) is CLOSED here — see `ensure-native!` and the
  measurement notes beside the `defcfn` block. Nothing in this namespace
  performs I/O, loads a library or resolves a C symbol until a handler is
  actually invoked through `perturb.effect/perform`. `perturb.noio` verifies
  that from outside, with a positive control."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [perturb.octet :as o]
            [perturb.cap :as cap]))

;; `os.name` is Chez's `(machine-type)` rendered by the host
;; (host/chez/java/host-static-methods.ss:672) — a compile-time constant, not a
;; `uname` call. Checked, because this is a load-time expression and the whole
;; point of this namespace now is that load time is quiet.
(def ^:private os-name (str/lower-case (or (System/getProperty "os.name") "")))
(def ^:private macos? (str/includes? os-name "mac"))

;; --- native binding: lazy, and reached only from inside a handler -----------
;;
;; MEASURED, NOT ASSUMED. `defcfn` does *not* resolve the C entry point when the
;; `def` evaluates. `jolt.ffi/defcfn` expands to `(def name (jolt.ffi/__cfn ...))`
;; and the backend lowers `__cfn` to
;;
;;   (let ((p #f)) (lambda (a0 ...) ((or p (begin (set! p (foreign-procedure ...)) p)) a0 ...)))
;;
;; (jolt-core/jolt/backend_scheme.clj:589-617, "Lazy resolution: the
;; foreign-procedure form is deferred inside a closure"; pinned by Jolt's own
;; test/chez/ffi-native-error-test.ss:61). Evaluating the `def` builds a closure
;; and touches nothing. `c-absent-canary` below is the live proof of that, kept
;; in the shipped namespace on purpose: it names a symbol that exists in no
;; object in this process. If a `def` resolved entry points, requiring
;; `perturb.posix` would fail outright and every run of this artifact would stop.
;; It does not. `perturb.noio` calls it and shows it throws — so resolution
;; happens at CALL, and "zero native calls" is exactly "zero symbols resolved".

(ffi/defcfn c-socket  "socket"  [:int :int :int] :int)
(ffi/defcfn c-connect "connect" [:int :pointer :int] :int :blocking)
(ffi/defcfn c-recv    "recv"    [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-send    "send"    [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-close   "close"   [:int] :int)

;; The canary. Never called by the handler; called by `perturb.noio` as a probe.
(ffi/defcfn c-absent-canary "perturb_absent_symbol_canary_do_not_define" [] :int)

(def native-log
  "Every crossing from this namespace into native code, counted.

  `:library-loads` counts calls to `jolt.ffi/load-library`, i.e. the thing I11
  used to do at namespace load. `:calls` counts invocations of the five syscall
  bindings — and because resolution is lazy and per-binding, a binding that was
  never called was never resolved. Nothing in perturb reads this; it exists so
  the no-I/O claim can be *measured* rather than argued (INHERITED I10 applies —
  it is a Jolt atom)."
  (atom {:library-loads 0 :calls 0 :by-op {}}))

(defn native-log-snapshot [] @native-log)
(defn reset-native-log! [] (reset! native-log {:library-loads 0 :calls 0 :by-op {}}))

(def ^:private lib-loaded (atom false))

(defn- ensure-native!
  "Load the process's own symbols, once, on first native use.

  This is the whole of I11's fix. It is reached only from the `sys-*` wrappers
  below, which are reached only from `handler` (and from `send-octets!` /
  `recv-octets`, which `handler` is the only caller of), which is reached only
  from `perturb.effect/perform`. So library loading now happens strictly inside
  the dynamic extent of a handled effect.

  Note what this deliberately does NOT do: it does not add a `:load-library` op
  to `perturb.wire/socket`. That effect is the contract the SCRIPTED handler
  also satisfies, and a scripted handler has no library. Declaring one would
  push a posix implementation detail into the interface the codec and session
  code name — the opposite of what §1.4 asks for."
  []
  (when-not @lib-loaded
    (reset! lib-loaded true)
    (swap! native-log update :library-loads inc)
    (ffi/load-library)))

(defn- count! [op]
  (swap! native-log (fn [m] (-> m (update :calls inc) (update-in [:by-op op] (fnil inc 0))))))

(defn- sys-socket  [d t p]     (ensure-native!) (count! :socket)  (c-socket d t p))
(defn- sys-connect [fd sa n]   (ensure-native!) (count! :connect) (c-connect fd sa n))
(defn- sys-send    [fd p n fl] (ensure-native!) (count! :send)    (c-send fd p n fl))
(defn- sys-recv    [fd p n fl] (ensure-native!) (count! :recv)    (c-recv fd p n fl))
(defn- sys-close   [fd]        (ensure-native!) (count! :close)   (c-close fd))

(defn absent-canary-probe
  "Call the canary symbol. Returns `:resolved` if it somehow came back (it
  cannot) or `[:threw ...]`. The point of the probe is the pair of facts:
  requiring this namespace succeeded, and calling this binding does not."
  []
  (try (c-absent-canary) :resolved
       (catch :default e [:threw (str e)])))

(def ^:private AF-INET 2)
(def ^:private SOCK-STREAM 1)

(defn- make-sockaddr
  "sockaddr_in for 127.0.0.1:port. INHERITED I7 — structurally jolt.nrepl's."
  [port]
  (let [sa (ffi/alloc 16)]
    (dotimes [i 16] (ffi/write sa :uint8 i 0))
    (if macos?
      (do (ffi/write sa :uint8 0 16) (ffi/write sa :uint8 1 AF-INET))
      (ffi/write sa :uint8 0 AF-INET))
    (ffi/write sa :uint8 2 (bit-and (bit-shift-right port 8) 0xff))
    (ffi/write sa :uint8 3 (bit-and port 0xff))
    (ffi/write sa :uint8 4 127)
    (ffi/write sa :uint8 7 1)
    sa))

;; --- the octet <-> native seam ----------------------------------------------
;; The native buffer is itself a capability: alloc/free exactly once, no use
;; after free. Recorded in the ledger; nothing checks it.

(def buffer-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.posix/NativeBuffer
       :perturb.cap/doc        "A foreign-alloc'd octet buffer."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate  {:states [:allocated :freed]
                                :initial :allocated
                                :terminal :freed
                                :transitions [{:op 'perturb.posix/with-buffer :from :allocated :to :freed}]}})))

(def ^:private buf-counter (atom 0))

(defn- with-buffer
  [n f]
  (let [id (str "nbuf-" (swap! buf-counter inc))
        p  (ffi/alloc (max 1 n))]
    (cap/transition! 'perturb.posix/NativeBuffer id nil :allocated :perturb.posix/alloc)
    (try (f p)
         (finally
           (ffi/free p)
           (cap/transition! 'perturb.posix/NativeBuffer id :allocated :freed :perturb.posix/free)))))

(defn send-octets!
  "Write an octet view into native memory one `:uint8` at a time, then send.
  No byte array, no narrowing, no fold."
  [fd ov]
  (let [n (o/ocount ov)]
    (with-buffer n
      (fn [p]
        (loop [i 0]
          (when (< i n)
            (ffi/write p :uint8 i (o/oref ov i))   ; <- octet in, octet stored
            (recur (inc i))))
        (loop [off 0]
          (if (>= off n)
            n
            (let [sent (sys-send fd (+ p off) (- n off) 0)]
              (if (pos? sent) (recur (+ off sent)) off))))))))

(defn recv-octets
  "recv into native memory, then read each byte back as `:uint8` -> 0..255."
  [fd max-n]
  (with-buffer max-n
    (fn [p]
      (let [got (sys-recv fd p max-n 0)]
        (if (pos? got)
          (o/octets (loop [i 0 acc []]
                      (if (< i got)
                        (recur (inc i) (conj acc (ffi/read p :uint8 i)))  ; <- unsigned read
                        acc)))
          o/empty-octets)))))

;; --- the handler ------------------------------------------------------------

(defn handler
  "A socket-effect handler over real sockets.

  `record` may be an atom; every send and recv payload is appended to it so the
  same octets can be replayed through the scripted handler.

  Constructing a handler does nothing native. The first native crossing on any
  path below goes through a `sys-*` wrapper, which calls `ensure-native!` — so
  the library load and every symbol resolution happen inside a handler
  invocation, which is inside `perturb.effect/perform`."
  ([] (handler nil))
  ([record]
   (fn [op site args]
     (let [note (fn [m] (when record (swap! record conj (assoc m :site site))) nil)]
       (cond
         (= op :connect)
         (let [host (first args) port (second args)]
           (if (not= host "127.0.0.1")
             [:abort {:reason :loopback-only :host host}]
             (let [fd (sys-socket AF-INET SOCK-STREAM 0)]
               (if (neg? fd)
                 [:abort {:reason :socket-failed}]
                 (let [sa (make-sockaddr port)
                       r  (sys-connect fd sa 16)]
                   (ffi/free sa)
                   (if (neg? r)
                     (do (sys-close fd) [:abort {:reason :connect-failed :port port}])
                     (do (note {:op :connect :port port}) [:ok fd])))))))

         (= op :send)
         (let [fd (first args) ov (second args)]
           (note {:op :send :octets ov})
           [:ok (send-octets! fd ov)])

         (= op :recv)
         (let [fd (first args) mx (second args)
               ov (recv-octets fd mx)]
           (note {:op :recv :octets ov})
           [:ok ov])

         (= op :close)
         (do (sys-close (first args)) (note {:op :close}) [:ok :closed])

         :else [:abort {:reason :unsupported-op :op op}])))))
