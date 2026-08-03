(ns perturb.posix
  "Handler (a): a real POSIX TCP socket.

  CLAIM 1's evidence lives in `send-octets!` and `recv-octets`. Both move wire
  bytes with `jolt.ffi`'s `:uint8`, which lowers to Chez `foreign-ref` /
  `foreign-set!` on `'unsigned-8`. Values are 0..255 in both directions and no
  fold happens at either end. Compare `jolt.nrepl`, which does
  `(byte-array (map int s))` on the way out — narrowing every octet above 0x7f
  to a negative slot via `na-byte-of` — and
  `(String. (ffi/read-array buf n) \"ISO-8859-1\")` on the way in.

  INHERITED I6, I7, I11: the FFI type table, the sockaddr layout copied from
  `jolt.nrepl`, and the load-time `load-library` side effect are all Jolt's, and
  I11 is a real hole in CLAIM 2 — binding the syscalls is I/O that happens at
  namespace load, outside any handler."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [perturb.octet :as o]
            [perturb.cap :as cap]))

(def ^:private os-name (str/lower-case (or (System/getProperty "os.name") "")))
(def ^:private macos? (str/includes? os-name "mac"))

;; INHERITED I11 — this performs I/O at namespace load, not inside a handler.
(ffi/load-library)

(ffi/defcfn c-socket  "socket"  [:int :int :int] :int)
(ffi/defcfn c-connect "connect" [:int :pointer :int] :int :blocking)
(ffi/defcfn c-recv    "recv"    [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-send    "send"    [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-close   "close"   [:int] :int)

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
            (let [sent (c-send fd (+ p off) (- n off) 0)]
              (if (pos? sent) (recur (+ off sent)) off))))))))

(defn recv-octets
  "recv into native memory, then read each byte back as `:uint8` -> 0..255."
  [fd max-n]
  (with-buffer max-n
    (fn [p]
      (let [got (c-recv fd p max-n 0)]
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
  same octets can be replayed through the scripted handler."
  ([] (handler nil))
  ([record]
   (fn [op site args]
     (let [note (fn [m] (when record (swap! record conj (assoc m :site site))) nil)]
       (cond
         (= op :connect)
         (let [host (first args) port (second args)]
           (if (not= host "127.0.0.1")
             [:abort {:reason :loopback-only :host host}]
             (let [fd (c-socket AF-INET SOCK-STREAM 0)]
               (if (neg? fd)
                 [:abort {:reason :socket-failed}]
                 (let [sa (make-sockaddr port)
                       r  (c-connect fd sa 16)]
                   (ffi/free sa)
                   (if (neg? r)
                     (do (c-close fd) [:abort {:reason :connect-failed :port port}])
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
         (do (c-close (first args)) (note {:op :close}) [:ok :closed])

         :else [:abort {:reason :unsupported-op :op op}])))))
