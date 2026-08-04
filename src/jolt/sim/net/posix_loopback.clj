(ns jolt.sim.net.posix-loopback
  "Deterministic simulation-only IPv4 loopback FFI handlers.

  This is NOT a socket implementation and never touches the OS. A world owns a
  shared jolt.sim.ffi-memory world plus deterministic POSIX socket state: high
  synthetic fds, ephemeral loopback ports, listeners with pending accepts, and
  bidirectional FIFO byte queues between paired connected sockets, each with an
  independent write-half-open flag. send/recv make at most two bytes of progress
  per call. Foreign functions are modeled before the OS exactly as
  jolt.sim.runtime :ffi-handlers.

  A caller supplies the exact live jolt.net target descriptor. No socket ABI
  constants or struct offsets are guessed from the host name. handlers returns
  the complete map: all native-memory operations plus the exact POSIX foreign
  signatures used by unchanged jolt.net code.

  Captured (capture? true) handlers return exact [result errno] pairs.
  getaddrinfo returns [gai-code native-error]; freeaddrinfo and close retain
  their ordinary scalar contracts. Empty
  accept/recv returns [-1 EAGAIN]; nonblocking connect establishes and queues a
  pending accept then returns [-1 EINPROGRESS]; a drained recv whose peer
  half-closed write returns [0 0] (EOF) while the reverse direction stays open.

  The self-pipe wake boundary is modeled the same way: pipe writes the two int
  fds at offsets 0 and 4 over one shared byte FIFO; write copies exactly len
  bytes (EPIPE once the reader closes); read drains that FIFO in bounded slices
  (EAGAIN while empty only after the reader is nonblocking, [0 0] EOF after the
  writer closes); and scalar close retires each endpoint idempotently while
  marking its peer's lifecycle. poll(2) uses the supplied target's exact pollfd
  layout and nfds_t width. A blocking poll registers under the world lock,
  releases that lock while parked, and recomputes readiness after every wake.
  Timeouts use real monotonic elapsed time; virtual time is not modeled here.

  sockaddr_in and addrinfo are written from the supplied descriptor, including
  Darwin's sin_len byte and its different ai_addr/ai_canonname order. Resolver
  ownership is tracked explicitly so only the returned head can free its nodes,
  and only once."
  (:require [jolt.sim.ffi-memory :as memory]))

;; ---- byte helpers (private copies; ffi-memory helpers are private) -----

(defn- ba->uvec [ba]
  (let [n (alength ba)]
    (loop [i 0 out (transient [])]
      (if (== i n)
        (persistent! out)
        (recur (inc i)
               (conj! out (bit-and 0xFF (int (aget ba i)))))))))

(defn- uvec->byte-array [v]
  (let [n (count v)
        arr (byte-array n)]
    (loop [i 0]
      (when (< i n)
        (aset arr i (nth v i))
        (recur (inc i))))
    arr))

(defn- be16 [v]
  [(bit-and (bit-shift-right v 8) 0xFF) (bit-and v 0xFF)])

(defn- be32 [v]
  [(bit-and (bit-shift-right v 24) 0xFF)
   (bit-and (bit-shift-right v 16) 0xFF)
   (bit-and (bit-shift-right v 8) 0xFF)
   (bit-and v 0xFF)])

(defn- u16 [bytes byte-order]
  (if (= :big byte-order)
    (+ (bit-shift-left (nth bytes 0) 8) (nth bytes 1))
    (+ (bit-shift-left (nth bytes 1) 8) (nth bytes 0))))

(defn- u32 [bytes byte-order]
  (if (= :big byte-order)
    (+ (bit-shift-left (nth bytes 0) 24)
       (bit-shift-left (nth bytes 1) 16)
       (bit-shift-left (nth bytes 2) 8)
       (nth bytes 3))
    (+ (nth bytes 0)
       (bit-shift-left (nth bytes 1) 8)
       (bit-shift-left (nth bytes 2) 16)
       (bit-shift-left (nth bytes 3) 24))))

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(def ^:private o-rdonly 0)
(def ^:private o-wronly 1)
(def ^:private o-rdwr 2)
(def ^:private loopback-addr 0x7f000001)

;; ---- exact jolt.net POSIX foreign-function keys ------------------------

(def ^:private common-handler-keys
 [[:foreign-function "getaddrinfo"
   [:pointer :pointer :pointer :pointer] :int true true]
  [:foreign-function "freeaddrinfo" [:pointer] :void false false]
  [:foreign-function "socket" [:int :int :int] :int false true]
  [:foreign-function "setsockopt"
   [:int :int :int :pointer :uint] :int false true]
  [:foreign-function "bind" [:int :pointer :uint] :int false true]
  [:foreign-function "listen" [:int :int] :int false true]
  [:foreign-function "fcntl" [:int :int :int] :int false true 2]
  [:foreign-function "getsockname"
   [:int :pointer :pointer] :int false true]
  [:foreign-function "getpeername"
   [:int :pointer :pointer] :int false true]
  [:foreign-function "connect" [:int :pointer :uint] :int true true]
  [:foreign-function "connect" [:int :pointer :uint] :int false true]
  [:foreign-function "accept"
   [:int :pointer :pointer] :int false true]
  [:foreign-function "getsockopt"
   [:int :int :int :pointer :pointer] :int false true]
  [:foreign-function "send"
   [:int :pointer :size_t :int] :ssize_t false true]
  [:foreign-function "recv"
   [:int :pointer :size_t :int] :ssize_t false true]
  [:foreign-function "shutdown" [:int :int] :int false true]
  [:foreign-function "close" [:int] :int false false]
  [:foreign-function "pipe" [:pointer] :int false true]
  [:foreign-function "read" [:int :pointer :size_t] :ssize_t false true]
  [:foreign-function "write" [:int :pointer :size_t] :ssize_t false true]])

(defn handler-keys
  "The exact POSIX loopback foreign-function handler keys for one validated
  target descriptor. poll(2)'s nfds_t is :size_t on Linux and :uint on Darwin,
  so registering both plausible signatures would weaken fail-closed routing."
  [target-descriptor]
  (conj common-handler-keys
        [:foreign-function "poll"
         [:pointer (:nfds-type target-descriptor) :int]
         :int true true]))

;; ---- captured result helpers -------------------------------------------

(defn- ok-pair [] [0 0])
(defn- err-pair [errno] [-1 errno])

;; ---- config ------------------------------------------------------------

(def ^:private supported-config-keys
  #{:first-fd :ephemeral-base :ephemeral-max :progress-limit
    :stream-capacity :pipe-capacity})

(def ^:private default-config
  {:first-fd 0x40000000
   :ephemeral-base 40000
   :ephemeral-max 65535
   :progress-limit 2
   ;; Each connected socket's receive FIFO is capped globally by a finite
   ;; positive-integer capacity. There is deliberately no nil/unbounded
   ;; compatibility mode: every world commits to one exact bound.
   :stream-capacity 65536
   ;; The self-pipe wake FIFO is capped the same way: one finite
   ;; positive-integer capacity, no nil/unbounded compatibility mode. A pipe
   ;; write either fits whole or fails; there is no partial pipe progress.
   :pipe-capacity 65536})

(defn- validate-config [config]
  (let [cfg (or config {})]
    (when-not (map? cfg)
      (fail! :jolt.sim.net.posix-loopback/invalid-config
             "posix-loopback world config must be a map"
             {:config cfg}))
    (let [unknown (vec (sort (remove supported-config-keys (keys cfg))))]
      (when (seq unknown)
        (fail! :jolt.sim.net.posix-loopback/invalid-config
               "posix-loopback world config contains unsupported keys"
               {:unknown-keys unknown})))
    (let [merged (merge default-config cfg)]
      (doseq [[k v] {:first-fd (:first-fd merged)
                     :ephemeral-base (:ephemeral-base merged)
                     :ephemeral-max (:ephemeral-max merged)
                     :progress-limit (:progress-limit merged)
                     :stream-capacity (:stream-capacity merged)
                     :pipe-capacity (:pipe-capacity merged)}]
        (when-not (and (integer? v) (pos? v))
          (fail! :jolt.sim.net.posix-loopback/invalid-config
                 (str "posix-loopback " (name k) " must be a positive integer")
                 {k v})))
      (when-not (> (:ephemeral-max merged) (:ephemeral-base merged))
        (fail! :jolt.sim.net.posix-loopback/invalid-config
               "posix-loopback :ephemeral-max must exceed :ephemeral-base"
               {:ephemeral-base (:ephemeral-base merged)
                :ephemeral-max (:ephemeral-max merged)}))
      merged)))

(def ^:private required-constants
  #{:af-inet :sock-stream :sol-socket :so-error
    :shut-rd :shut-wr :shut-rdwr :f-getfl :f-setfl :o-nonblock
    :pollin :pollout :pollerr :pollhup :pollnval})

(def ^:private required-errnos
  #{:eagain :einprogress :eaddrinuse :econnrefused :econnreset :epipe})

(defn- validate-target-descriptor [d]
  (when-not (and (map? d) (= :posix (:platform d)))
    (fail! :jolt.sim.net.posix-loopback/invalid-target
           "posix-loopback requires a POSIX jolt.net target descriptor"
           {:target-descriptor d}))
  (when-not (= :uint (:socklen-type d))
    (fail! :jolt.sim.net.posix-loopback/invalid-target
           "posix-loopback requires the current POSIX uint socklen_t contract"
           {:socklen-type (:socklen-type d)}))
  (let [missing-constants
        (vec (sort (remove #(integer? (get-in d [:const %])) required-constants)))
        missing-errnos
        (vec (sort (remove #(integer? (get-in d [:errno %])) required-errnos)))
        sockaddr (get-in d [:layout :sockaddr-in])
        pollfd (get-in d [:layout :pollfd])
        addrinfo (get-in d [:layout :addrinfo])]
    (when (seq missing-constants)
      (fail! :jolt.sim.net.posix-loopback/invalid-target
             "target descriptor lacks required POSIX constants"
             {:missing-constants missing-constants}))
    (when (seq missing-errnos)
      (fail! :jolt.sim.net.posix-loopback/invalid-target
             "target descriptor lacks required POSIX errno values"
             {:missing-errnos missing-errnos}))
    (when-not (contains? #{:size_t :uint} (:nfds-type d))
      (fail! :jolt.sim.net.posix-loopback/invalid-target
             "target descriptor has an unsupported POSIX nfds_t type"
             {:nfds-type (:nfds-type d)}))
    (when-not (and (= 16 (:size sockaddr))
                   (integer? (:family sockaddr))
                   (= 2 (:port sockaddr))
                   (= 4 (:addr sockaddr)))
      (fail! :jolt.sim.net.posix-loopback/invalid-target
             "target descriptor has an unsupported sockaddr_in layout"
             {:layout sockaddr}))
    (when-not (= {:size 8 :fd 0 :events 4 :revents 6} pollfd)
      (fail! :jolt.sim.net.posix-loopback/invalid-target
             "target descriptor has an unsupported pollfd layout"
             {:layout pollfd}))
    (when-not (and (= 48 (:size addrinfo))
                   (every? integer?
                           (map addrinfo
                                [:flags :family :socktype :protocol :addrlen
                                 :addr :canonname :next]))
                   (keyword? (:addrlen-type addrinfo)))
      (fail! :jolt.sim.net.posix-loopback/invalid-target
             "target descriptor has an unsupported addrinfo layout"
             {:layout addrinfo})))
  d)

;; ---- world -------------------------------------------------------------

(def ^:private world-keys
  #{:memory :memory-handlers :state :readiness :lock :target :config :type})

(def ^:private memory-world-keys #{:state :lock :config :type})
(def ^:private memory-config-keys
  #{:byte-order :pointer-size :long-size :base-address
    :alignment :available-libraries})
(def ^:private memory-state-keys #{:next-base :heap :loaded-libraries})
(def ^:private world-state-keys
  #{:next-fd :next-ephemeral :sockets :pipes :listeners :closed-fds
    :addrinfo-allocations :capacity-evidence :pipe-evidence})
(def ^:private capacity-evidence-keys
  #{:stream-capacity :stream-would-blocks
    :stream-capacity-limited-writes :max-stream-recv-bytes})
(def ^:private pipe-evidence-keys
  #{:pipe-capacity :pipe-would-blocks :max-pipe-fifo-bytes})
(def ^:private readiness-state-keys #{:epoch :next-waiter :waiters})

(defn- deref-world-field [value field]
  (try
    @value
    (catch :default _
      (fail! :jolt.sim.net.posix-loopback/invalid-world
             "posix-loopback world contains a non-derefable state field"
             {:field field}))))

(defn world
  "Returns a deterministic IPv4 loopback world for one exact live jolt.net
  POSIX target descriptor. An optional shared memory world and config map let a
  harness select ABI widths and deterministic resource ranges explicitly."
  ([target-descriptor]
   (world (memory/world) target-descriptor nil))
  ([memory-world target-descriptor]
   (world memory-world target-descriptor nil))
  ([memory-world target-descriptor config]
   (when-not (and (map? memory-world) (map? (:config memory-world)))
     (fail! :jolt.sim.net.posix-loopback/invalid-world
            "first argument must be a jolt.sim.ffi-memory world"
            {:provided memory-world}))
   (when-not (= 8 (get-in memory-world [:config :pointer-size]))
     (fail! :jolt.sim.net.posix-loopback/invalid-world
            "current POSIX addrinfo modeling requires an LP64 memory world"
            {:pointer-size (get-in memory-world [:config :pointer-size])}))
   (let [cfg (validate-config config)
         target (validate-target-descriptor target-descriptor)]
     {:memory memory-world
      :memory-handlers (memory/handlers memory-world)
      :state (atom {:next-fd (:first-fd cfg)
                    :next-ephemeral (:ephemeral-base cfg)
                    :sockets {}
                    :pipes {}
                    :listeners {}
                    :closed-fds #{}
                    :addrinfo-allocations {}
                    :capacity-evidence
                    {:stream-capacity (:stream-capacity cfg)
                     :stream-would-blocks 0
                     :stream-capacity-limited-writes 0
                     :max-stream-recv-bytes 0}
                    :pipe-evidence
                    {:pipe-capacity (:pipe-capacity cfg)
                     :pipe-would-blocks 0
                     :max-pipe-fifo-bytes 0}})
      ;; Waiters deliberately live outside the public resource state. They are
      ;; host synchronization objects, not simulated POSIX resources or trace
      ;; values, and must never leak into a replay snapshot.
      :readiness (atom {:epoch 0 :next-waiter 0 :waiters {}})
      :lock (Object.)
      :target target
      :config cfg
      :type ::world})))

(defn validate-world
  "Validates and returns a live world produced by [[world]].

  Boundary frontends use this before retaining a caller-supplied world. The
  validator checks the exact closed outer shape, the shared memory-world and
  handler registry shapes, the target and config contracts, and the coherent
  top-level shapes of mutable resource and readiness state. It deliberately
  does not snapshot or otherwise mutate live modeled resources."
  [value]
  (when-not (and (map? value)
                 (= world-keys (set (keys value)))
                 (= ::world (:type value))
                 (some? (:lock value)))
    (fail! :jolt.sim.net.posix-loopback/invalid-world
           "value is not an exact posix-loopback world"
           {:provided-class (str (class value))}))
  (let [memory-world (:memory value)
        memory-state
        (when (and (map? memory-world)
                   (= memory-world-keys (set (keys memory-world)))
                   (= :jolt.sim.ffi-memory/world (:type memory-world))
                   (some? (:lock memory-world)))
          (deref-world-field (:state memory-world) :memory-state))]
    (when-not (and (map? memory-state)
                   (= memory-state-keys (set (keys memory-state)))
                   (integer? (:next-base memory-state))
                   (pos? (:next-base memory-state))
                   (map? (:heap memory-state))
                   (set? (:loaded-libraries memory-state))
                   (map? (:config memory-world))
                   (= memory-config-keys
                      (set (keys (:config memory-world))))
                   (= 8 (get-in memory-world [:config :pointer-size])))
      (fail! :jolt.sim.net.posix-loopback/invalid-world
             "posix-loopback world contains an invalid shared memory world"
             {}))
    (when-not (and (map? (:memory-handlers value))
                   (= (set (keys (memory/handlers memory-world)))
                      (set (keys (:memory-handlers value)))))
      (fail! :jolt.sim.net.posix-loopback/invalid-world
             "posix-loopback world contains an invalid memory handler registry"
             {})))
  (let [validated-config (validate-config (:config value))]
    (when-not (= validated-config (:config value))
      (fail! :jolt.sim.net.posix-loopback/invalid-world
             "posix-loopback world config is not canonical"
             {:config (:config value)})))
  (validate-target-descriptor (:target value))
  (let [state (deref-world-field (:state value) :state)
        readiness (deref-world-field (:readiness value) :readiness)
        capacity (:capacity-evidence state)
        pipe-capacity (:pipe-evidence state)]
    (when-not
     (and (map? state)
          (= world-state-keys (set (keys state)))
          (integer? (:next-fd state))
          (pos? (:next-fd state))
          (integer? (:next-ephemeral state))
          (pos? (:next-ephemeral state))
          (every? map? (map state [:sockets :pipes :listeners
                                   :addrinfo-allocations]))
          (set? (:closed-fds state))
          (map? capacity)
          (= capacity-evidence-keys (set (keys capacity)))
          (every? #(and (integer? %) (not (neg? %))) (vals capacity))
          (pos? (:stream-capacity capacity))
          (map? pipe-capacity)
          (= pipe-evidence-keys (set (keys pipe-capacity)))
          (every? #(and (integer? %) (not (neg? %))) (vals pipe-capacity))
          (pos? (:pipe-capacity pipe-capacity)))
      (fail! :jolt.sim.net.posix-loopback/invalid-world
             "posix-loopback world contains invalid resource state"
             {}))
    (when-not (and (map? readiness)
                   (= readiness-state-keys (set (keys readiness)))
                   (integer? (:epoch readiness))
                   (not (neg? (:epoch readiness)))
                   (integer? (:next-waiter readiness))
                   (not (neg? (:next-waiter readiness)))
                   (map? (:waiters readiness)))
      (fail! :jolt.sim.net.posix-loopback/invalid-world
             "posix-loopback world contains invalid readiness state"
             {})))
  value)

;; ---- memory bridge -----------------------------------------------------

(defn- invoke-mem [w op args]
  (let [h (get (:memory-handlers w) [:native-operation op])]
    (h {:kind :native-operation :task 0 :arguments args :operation op})))

(defn- byte-order [w]
  (get-in w [:memory :config :byte-order]))

(defn- target-const [w k]
  (get-in w [:target :const k]))

(defn- target-errno [w k]
  (get-in w [:target :errno k]))

(defn- native-error [w k]
  (or (target-errno w k)
      (fail! :jolt.sim.net.posix-loopback/missing-errno
             "target descriptor lacks an exact errno for this unsupported transition"
             {:errno k})))

(defn- addrinfo-layout [w]
  (get-in w [:target :layout :addrinfo]))

(defn- sockaddr-layout [w]
  (get-in w [:target :layout :sockaddr-in]))

;; ---- sockaddr_in layout ------------------------------------------------

(defn- sockaddr-in-bytes [w family port addr]
  (let [layout (sockaddr-layout w)
        byte-order (byte-order w)
        fam (if (:sin-len? (:target w))
              [(:size layout) family]
              (if (= :big byte-order)
                (be16 family)
                [(bit-and family 0xFF)
                 (bit-and (bit-shift-right family 8) 0xFF)]))
        prt (be16 port)
        ad (be32 addr)]
    (vec (concat fam prt ad (repeat 8 0)))))

(defn- write-sockaddr-in! [w ptr family port addr]
  (invoke-mem w :write-array
              [ptr (uvec->byte-array
                    (sockaddr-in-bytes w family port addr))]))

(defn- parse-sockaddr-in [w ptr]
  (let [bo (byte-order w)
        layout (sockaddr-layout w)
        v (ba->uvec (invoke-mem w :read-array [ptr (:size layout)]))]
    {:family (if (:sin-len? (:target w))
               (nth v (:family layout))
               (u16 (subvec v (:family layout) (+ 2 (:family layout))) bo))
     :port (u16 (subvec v (:port layout) (+ 2 (:port layout))) :big)
     :addr (u32 (subvec v (:addr layout) (+ 4 (:addr layout))) :big)}))

;; ---- addrinfo layout ---------------------------------------------------

(defn- write-addrinfo! [w base fields]
  (let [{:keys [flags family socktype protocol addrlen addr canonname next]}
        fields
        layout (addrinfo-layout w)]
    (invoke-mem w :write [base :int (:flags layout) flags])
    (invoke-mem w :write [base :int (:family layout) family])
    (invoke-mem w :write [base :int (:socktype layout) socktype])
    (invoke-mem w :write [base :int (:protocol layout) protocol])
    (invoke-mem w :write
                [base (:addrlen-type layout) (:addrlen layout) addrlen])
    (invoke-mem w :write [base :pointer (:addr layout) addr])
    (invoke-mem w :write [base :pointer (:canonname layout) canonname])
    (invoke-mem w :write [base :pointer (:next layout) next])))

;; ---- deterministic resource allocation ---------------------------------

(defn- claim-fd! [w]
  (let [a (:state w)]
    (loop []
      (let [cur @a
            fd (:next-fd cur)]
        (if (compare-and-set! a cur (assoc cur :next-fd (inc fd)))
          fd
          (recur))))))

(defn- claim-ephemeral! [w]
  (let [a (:state w)
        {:keys [ephemeral-base ephemeral-max]} (:config w)
        capacity (inc (- ephemeral-max ephemeral-base))]
    (loop [p (:next-ephemeral @a) attempts 0]
      (when (>= attempts capacity)
        (fail! :jolt.sim.net.posix-loopback/ports-exhausted
               "the deterministic loopback ephemeral range is exhausted"
               {:ephemeral-base ephemeral-base
                :ephemeral-max ephemeral-max}))
      (let [used (into #{} (keep :local-port (vals (:sockets @a))))
            nxt (if (>= p ephemeral-max) ephemeral-base (inc p))]
        (if (contains? used p)
          (recur nxt (inc attempts))
          (do
            (swap! a assoc :next-ephemeral nxt)
            p))))))

(defn- socket-of [w fd]
  (get-in @(:state w) [:sockets fd]))

(defn- pipe-of [w fd]
  (get-in @(:state w) [:pipes fd]))

(defn- fd-resource [w fd]
  (cond
    (socket-of w fd)
    {:kind :socket :entry (socket-of w fd) :path [:sockets fd]}

    (pipe-of w fd)
    {:kind :pipe :entry (pipe-of w fd) :path [:pipes fd]}

    :else nil))

(defn- with-socket [w fd f]
  (if-let [s (socket-of w fd)]
    (f s)
    (err-pair (native-error w :ebadf))))

;; ---- readiness waiters -------------------------------------------------

(defn- notify-readiness!
  "Advance the readiness epoch and release every currently parked poll call.
  The caller holds the world lock, so clearing the waiter set before delivery
  forms one atomic publish-before-wake boundary with the resource transition."
  [w]
  (let [readiness (:readiness w)
        current @readiness
        waiters (vals (:waiters current))]
    (reset! readiness
            (-> current
                (update :epoch inc)
                (assoc :waiters {})))
    (doseq [waiter waiters]
      (deliver waiter true))
    nil))

(defn- register-readiness-waiter! [w]
  (let [readiness (:readiness w)
        current @readiness
        id (:next-waiter current)
        signal (promise)]
    (reset! readiness
            (-> current
                (assoc :next-waiter (inc id))
                (assoc-in [:waiters id] signal)))
    {:id id :signal signal}))

(defn- remove-readiness-waiter! [w id]
  (swap! (:readiness w) update :waiters dissoc id)
  nil)

(defn- requested? [events flag]
  (not (zero? (bit-and events flag))))

(defn- pipe-poll-bits [w endpoint events]
  (let [pollin (target-const w :pollin)
        pollout (target-const w :pollout)
        pollerr (target-const w :pollerr)
        pollhup (target-const w :pollhup)]
    (case (:role endpoint)
      :reader
      (bit-or
       (if (and (requested? events pollin)
                (seq (:fifo endpoint)))
         pollin 0)
       ;; POSIX reports hangup independently of the requested interests. Any
       ;; bytes queued before writer close remain readable alongside POLLHUP.
       (if (:writer-closed? endpoint) pollhup 0))

      :writer
      (let [capacity (:pipe-capacity (:config w))
            reader (pipe-of w (:peer-fd endpoint))
            reader-gone? (or (:reader-closed? endpoint) (nil? reader))
            ;; POLLOUT is present for a live reader only while its FIFO has
            ;; room. A closed or missing reader still exposes POLLOUT alongside
            ;; POLLERR so the subsequent write reveals EPIPE rather than EAGAIN.
            pollout? (if reader-gone?
                       (requested? events pollout)
                       (and (requested? events pollout)
                            (pos? (- capacity (count (:fifo reader))))))]
        (bit-or
         (if pollout? pollout 0)
         (if reader-gone? pollerr 0)))

      0)))

(defn- socket-poll-bits [w socket events]
  (let [pollin (target-const w :pollin)
        pollout (target-const w :pollout)
        pollhup (target-const w :pollhup)]
    (case (:state socket)
      :listening
      (if (and (requested? events pollin)
               (seq (:accept-q socket)))
        pollin 0)

      :connected
      (let [readable? (or (seq (:recv-q socket))
                          (:read-shutdown? socket)
                          (:peer-write-shutdown? socket)
                          (:peer-closed? socket))
            capacity (:stream-capacity (:config w))
            peer (socket-of w (:peer-fd socket))
            ;; POLLOUT is present for a live readable peer only while its
            ;; receive FIFO has room. A missing/closed peer, or one that
            ;; stopped reading, remains writable in poll's sense so the
            ;; subsequent send exposes EPIPE; local write shutdown is never
            ;; writable.
            writable?
            (and (not (:write-shutdown? socket))
                 (or (nil? peer)
                     (:read-shutdown? peer)
                     (pos? (- capacity (count (:recv-q peer))))))]
        (bit-or
         (if (and (requested? events pollin) readable?) pollin 0)
         (if (and (requested? events pollout) writable?) pollout 0)
         (if (:peer-closed? socket) pollhup 0)))

      0)))

(defn- poll-bits [w fd events]
  (if (neg? fd)
    ;; poll(2) uses a negative fd as a caller-owned disabled slot.
    0
    (if-let [{:keys [kind entry]} (fd-resource w fd)]
      (case kind
        :socket (socket-poll-bits w entry events)
        :pipe (pipe-poll-bits w entry events)
        0)
      ;; POLLNVAL is reported for each invalid descriptor regardless of the
      ;; requested mask; poll itself still succeeds and counts that entry.
      (target-const w :pollnval))))

(defn- poll-ready-count! [w buf n]
  (let [{:keys [size fd events revents]}
        (get-in w [:target :layout :pollfd])]
    (loop [idx 0 ready 0]
      (if (= idx n)
        ready
        (let [entry (+ buf (* idx size))
              raw-fd (invoke-mem w :read [entry :int fd])
              interests (invoke-mem w :read [entry :int16 events])
              bits (poll-bits w raw-fd interests)]
          (invoke-mem w :write [entry :int16 revents bits])
          (recur (inc idx) (if (zero? bits) ready (inc ready))))))))

(defn- monotonic-nanos []
  (System/nanoTime))

(defn- remaining-wait-ms [deadline]
  (let [remaining (- deadline (monotonic-nanos))]
    (if (pos? remaining)
      (max 1 (quot (+ remaining 999999) 1000000))
      0)))

(defn- h-poll [w {:keys [arguments]}]
  (let [[buf n timeout-ms] (vec arguments)]
    (when-not (and (integer? n) (not (neg? n)))
      (fail! :jolt.sim.net.posix-loopback/invalid-poll
             "poll entry count must be a non-negative integer"
             {:count n}))
    (when-not (and (integer? timeout-ms) (>= timeout-ms -1))
      (fail! :jolt.sim.net.posix-loopback/invalid-poll
             "poll timeout must be -1 or a non-negative integer"
             {:timeout-ms timeout-ms}))
    (when (and (pos? n) (zero? buf))
      (fail! :jolt.sim.net.posix-loopback/invalid-poll
             "a non-empty poll array requires a non-null buffer"
             {:count n :buffer buf}))
    (let [deadline (when-not (= -1 timeout-ms)
                     (+ (monotonic-nanos) (* timeout-ms 1000000)))]
      (loop []
        (let [step
              (locking (:lock w)
                (let [ready (poll-ready-count! w buf n)]
                  (cond
                    (pos? ready) {:result [ready 0]}
                    (zero? timeout-ms) {:result [0 0]}
                    (and deadline (zero? (remaining-wait-ms deadline)))
                    {:result [0 0]}
                    :else {:waiter (register-readiness-waiter! w)})))]
          (if-let [result (:result step)]
            result
            (let [{:keys [id signal]} (:waiter step)]
              (try
                (if deadline
                  (deref signal (remaining-wait-ms deadline) ::poll-timeout)
                  @signal)
                (finally
                  ;; Timeout, cancellation, and notification all converge on
                  ;; the same cleanup. If a publisher already cleared this id,
                  ;; dissoc is harmless. The next loop always recomputes bits.
                  (locking (:lock w)
                    (remove-readiness-waiter! w id))))
              (recur))))))))

;; ---- per-function handlers ---------------------------------------------

(defn- h-getaddrinfo [w {:keys [arguments]}]
  (let [[node-ptr service-ptr hints-ptr res-out-ptr] (vec arguments)
        _node (when-not (zero? node-ptr)
                (invoke-mem w :ptr->string [node-ptr]))
        service (when-not (zero? service-ptr)
                  (invoke-mem w :ptr->string [service-ptr]))
        port (if service (or (parse-long service) 0) 0)
        layout (addrinfo-layout w)
        sockaddr-size (:size (sockaddr-layout w))
        hints (when-not (zero? hints-ptr)
                {:flags (invoke-mem w :read
                                    [hints-ptr :int (:flags layout)])
                 :family (invoke-mem w :read
                                     [hints-ptr :int (:family layout)])
                 :socktype (invoke-mem w :read
                                       [hints-ptr :int (:socktype layout)])})
        family (target-const w :af-inet)
        socktype (or (:socktype hints) (target-const w :sock-stream))
        sockaddr-ptr (invoke-mem w :alloc [sockaddr-size])]
    (write-sockaddr-in! w sockaddr-ptr family port loopback-addr)
    (let [ai-ptr (invoke-mem w :alloc [(:size layout)])]
      (write-addrinfo!
       w ai-ptr
       {:flags (or (:flags hints) 0)
        :family family
        :socktype socktype
        :protocol 0
        :addrlen sockaddr-size
        :addr sockaddr-ptr
        :canonname 0
        :next 0})
      (invoke-mem w :write [res-out-ptr :pointer 0 ai-ptr])
      (swap! (:state w) assoc-in [:addrinfo-allocations ai-ptr]
             [sockaddr-ptr ai-ptr])
      [0 0])))

(defn- h-freeaddrinfo [w {:keys [arguments]}]
  (let [[head] (vec arguments)]
    (when-not (zero? head)
      (if-let [allocations
               (get-in @(:state w) [:addrinfo-allocations head])]
        (do
          (doseq [address allocations]
            (invoke-mem w :free [address]))
          (swap! (:state w) update :addrinfo-allocations dissoc head))
        (fail! :jolt.sim.net.posix-loopback/unknown-addrinfo
               "freeaddrinfo requires one live head returned by this world"
               {:head head})))
    nil))

(defn- h-socket [w {:keys [arguments]}]
  (let [[domain type protocol] (vec arguments)]
    (if (and (= domain (target-const w :af-inet))
             (= type (target-const w :sock-stream)))
      (let [fd (claim-fd! w)]
        (swap! (:state w) assoc-in [:sockets fd]
               {:fd fd :domain domain :type type :protocol protocol
                :state :created
                :local-port nil :peer-port nil :peer-fd nil
                :nonblocking? false
                :write-shutdown? false :read-shutdown? false
                :peer-write-shutdown? false :peer-closed? false
                :recv-q [] :accept-q [] :options {}})
        [fd 0])
      (err-pair (native-error w :eafnosupport)))))

(defn- h-setsockopt [w {:keys [arguments]}]
  (let [[fd level opt value-ptr value-len] (vec arguments)]
    (with-socket
     w fd
     (fn [_]
       (if (< value-len 4)
         (err-pair (native-error w :einval))
         (let [value (invoke-mem w :read [value-ptr :int 0])]
           (swap! (:state w) assoc-in [:sockets fd :options [level opt]] value)
           (ok-pair)))))))

(defn- bind-result [w s fd sa]
  (let [used (into #{} (keep :local-port (vals (:sockets @(:state w)))))
        req (:port sa)]
    (cond
      (not= :created (:state s))
      (err-pair (native-error w :einval))

      (not= (target-const w :af-inet) (:family sa))
      (err-pair (native-error w :eafnosupport))

      (and (pos? req) (contains? used req))
      (err-pair (target-errno w :eaddrinuse))

      :else
      (let [port (if (zero? req) (claim-ephemeral! w) req)]
        (swap! (:state w) assoc-in [:sockets fd]
               (assoc s :state :bound :local-port port))
        (ok-pair)))))

(defn- h-bind [w {:keys [arguments]}]
  (let [[fd addr-ptr _addrlen] (vec arguments)]
    (with-socket w fd
                 (fn [s]
                   (bind-result w s fd (parse-sockaddr-in w addr-ptr))))))

(defn- h-listen [w {:keys [arguments]}]
  (let [[fd _backlog] (vec arguments)]
    (with-socket w fd
                 (fn [s]
                   (if (and (#{:bound :listening} (:state s)) (:local-port s))
                     (do (swap! (:state w) assoc-in [:sockets fd :state] :listening)
                         (swap! (:state w) assoc-in [:listeners (:local-port s)] fd)
                         (notify-readiness! w)
                         (ok-pair))
                     (err-pair (native-error w :einval)))))))

(defn- h-fcntl [w {:keys [arguments]}]
  (let [[fd cmd arg] (vec arguments)
        o-nonblock (target-const w :o-nonblock)]
    (if-let [{:keys [kind entry path]} (fd-resource w fd)]
      (let [access-mode
            (if (= :socket kind)
              o-rdwr
              (case (:role entry)
                :reader o-rdonly
                :writer o-wronly))]
        (cond
          (= cmd (target-const w :f-getfl))
          [(bit-or access-mode
                   (if (:nonblocking? entry) o-nonblock 0))
           0]

          (= cmd (target-const w :f-setfl))
          (do
            (swap! (:state w) assoc-in (conj path :nonblocking?)
                   (not (zero? (bit-and arg o-nonblock))))
            (ok-pair))

          :else (err-pair (native-error w :einval))))
      (err-pair (native-error w :ebadf)))))

(defn- write-name-result [w addr-ptr addrlen-ptr port addr]
  (write-sockaddr-in!
   w addr-ptr (target-const w :af-inet) port addr)
  (when-not (zero? addrlen-ptr)
    (invoke-mem w :write
                [addrlen-ptr (:socklen-type (:target w)) 0
                 (:size (sockaddr-layout w))]))
  (ok-pair))

(defn- h-getsockname [w {:keys [arguments]}]
  (let [[fd addr-ptr addrlen-ptr] (vec arguments)]
    (with-socket
     w fd
     (fn [s]
       (if-let [port (:local-port s)]
         (write-name-result w addr-ptr addrlen-ptr port loopback-addr)
         (write-name-result w addr-ptr addrlen-ptr 0 0))))))

(defn- h-getpeername [w {:keys [arguments]}]
  (let [[fd addr-ptr addrlen-ptr] (vec arguments)]
    (with-socket
     w fd
     (fn [s]
       (cond
         (not= :connected (:state s))
         (err-pair (native-error w :enotconn))
         :else (write-name-result
                w addr-ptr addrlen-ptr (:peer-port s) loopback-addr))))))

(defn- connect-result [w fd s sa]
  (let [listener-fd (get (:listeners @(:state w)) (:port sa))]
    (cond
      (= :connected (:state s))
      (err-pair (native-error w :eisconn))

      (not= (target-const w :af-inet) (:family sa))
      (err-pair (native-error w :eafnosupport))

      (or (nil? listener-fd) (not= loopback-addr (:addr sa)))
      (err-pair (target-errno w :econnrefused))

      :else
      (let [client-port (or (:local-port s) (claim-ephemeral! w))
            server-fd (claim-fd! w)]
        (swap! (:state w) assoc-in [:sockets fd]
               (assoc s :state :connected
                        :local-port client-port
                        :peer-port (:port sa)
                        :peer-fd server-fd))
        (swap! (:state w) assoc-in [:sockets server-fd]
               {:fd server-fd :domain (:domain s) :type (:type s)
                :protocol (:protocol s) :state :connected
                :local-port (:port sa) :peer-port client-port :peer-fd fd
                :nonblocking? false :write-shutdown? false
                :read-shutdown? false :peer-write-shutdown? false
                :peer-closed? false :accepted? false
                :recv-q [] :accept-q [] :options {}})
        (swap! (:state w) update-in [:sockets listener-fd :accept-q]
               (fn [q] (conj (or q []) server-fd)))
        (notify-readiness! w)
        (if (:nonblocking? s)
          (err-pair (target-errno w :einprogress))
          (ok-pair))))))

(defn- h-connect [w {:keys [arguments]}]
  (let [[fd addr-ptr _addrlen] (vec arguments)]
    (with-socket w fd
                 (fn [s]
                   (connect-result w fd s (parse-sockaddr-in w addr-ptr))))))

(defn- accept-result [w listener s addr-ptr addrlen-ptr]
  (cond
    (not= :listening (:state s))
    (err-pair (native-error w :einval))

    (empty? (:accept-q s))
    (err-pair (target-errno w :eagain))

    :else
    (let [server-fd (first (:accept-q s))
          server (socket-of w server-fd)]
      (swap! (:state w) assoc-in [:sockets listener :accept-q]
             (subvec (:accept-q s) 1))
      (swap! (:state w) assoc-in [:sockets server-fd :accepted?] true)
      (notify-readiness! w)
      (if (zero? addr-ptr)
        [server-fd 0]
        (do (write-name-result
             w addr-ptr addrlen-ptr (:peer-port server) loopback-addr)
            [server-fd 0])))))

(defn- h-accept [w {:keys [arguments]}]
  (let [[listener addr-ptr addrlen-ptr] (vec arguments)]
    (with-socket w listener
                 (fn [s]
                   (accept-result w listener s addr-ptr addrlen-ptr)))))

(defn- h-getsockopt [w {:keys [arguments]}]
  (let [[fd level optname optval-ptr optlen-ptr] (vec arguments)]
    (with-socket
     w fd
     (fn [_]
       (if (and (= level (target-const w :sol-socket))
                (= optname (target-const w :so-error)))
         (let [width (invoke-mem w :sizeof [:int])]
           (invoke-mem w :write [optval-ptr :int 0 0])
           (when-not (zero? optlen-ptr)
             (invoke-mem w :write
                         [optlen-ptr (:socklen-type (:target w)) 0 width]))
           (ok-pair))
         (err-pair (native-error w :einval)))))))

(defn- send-result [w s buf-ptr len]
  (cond
    (not= :connected (:state s))
    (err-pair (native-error w :enotconn))

    (:write-shutdown? s)
    (err-pair (target-errno w :epipe))

    (zero? len) [0 0]

    :else
    (if-let [peer (socket-of w (:peer-fd s))]
      (if (:read-shutdown? peer)
        ;; EPIPE precedence: a peer that stopped reading is honored before
        ;; the receive-FIFO capacity model.
        (err-pair (target-errno w :epipe))
        (let [capacity (:stream-capacity (:config w))
              occupied (count (:recv-q peer))
              room (- capacity occupied)]
          (if (zero? room)
            ;; The peer's receive FIFO is full. A nonblocking descriptor
            ;; captures EAGAIN exactly; a blocking one cannot wait inside the
            ;; model and fails closed with a typed transition.
            (do (swap! (:state w)
                       update :capacity-evidence
                       (fn [ev] (update ev :stream-would-blocks inc)))
                (if (:nonblocking? s)
                  (err-pair (target-errno w :eagain))
                  (fail! :jolt.sim.net.posix-loopback/blocking-stream-send-unsupported
                         "blocking stream send into a full receive FIFO requires the modeled poll/wait slice"
                         {:fd (:fd s) :peer-fd (:fd peer) :capacity capacity})))
            (let [progress-limit (:progress-limit (:config w))
                  wanted (min len progress-limit)
                  n (min wanted room)
                  ;; Capacity-limited means room reduced the progress that
                  ;; min(length, progress-limit) otherwise allowed.
                  capacity-limited? (< n wanted)
                  moved (ba->uvec (invoke-mem w :read-array [buf-ptr n]))]
              ;; The queue and its evidence transition in one state swap so a
              ;; concurrent readiness observer never sees occupancy without
              ;; its matching evidence.
              (swap! (:state w)
                     (fn [cur]
                       (let [new-occ (+ occupied n)]
                         (-> cur
                             (update-in [:sockets (:fd peer) :recv-q]
                                        #(into % moved))
                             (update :capacity-evidence
                                     (fn [ev]
                                       (cond-> (assoc ev :max-stream-recv-bytes
                                                      (max (:max-stream-recv-bytes ev)
                                                           new-occ))
                                         capacity-limited?
                                         (update :stream-capacity-limited-writes inc))))))))
              (notify-readiness! w)
              [n 0]))))
      ;; Missing peer (closed): EPIPE precedence before capacity.
      (err-pair (target-errno w :epipe)))))

(defn- h-send [w {:keys [arguments]}]
  (let [[fd buf-ptr len _flags] (vec arguments)]
    (with-socket w fd
                 (fn [s] (send-result w s buf-ptr len)))))

(defn- recv-result [w s buf-ptr len]
  (cond
    (not= :connected (:state s))
    (err-pair (native-error w :enotconn))

    (zero? len) [0 0]

    (:read-shutdown? s) [0 0]

    :else
    (let [q (:recv-q s)]
      (if (zero? (count q))
        (if (or (:peer-write-shutdown? s) (:peer-closed? s))
          [0 0]
          (err-pair (target-errno w :eagain)))
        (let [n (min len (:progress-limit (:config w)) (count q))
              got (subvec q 0 n)]
          (swap! (:state w) assoc-in [:sockets (:fd s) :recv-q]
                 (subvec q n))
          (invoke-mem w :write-array [buf-ptr (uvec->byte-array got)])
          (notify-readiness! w)
          [n 0])))))

(defn- h-recv [w {:keys [arguments]}]
  (let [[fd buf-ptr len _flags] (vec arguments)]
    (with-socket w fd
                 (fn [s] (recv-result w s buf-ptr len)))))

(defn- h-shutdown [w {:keys [arguments]}]
  (let [[fd how] (vec arguments)]
    (with-socket
     w fd
     (fn [s]
       (cond
         (not= :connected (:state s))
         (err-pair (native-error w :enotconn))

         :else
         (let [write? (or (= how (target-const w :shut-wr))
                          (= how (target-const w :shut-rdwr)))
               read? (or (= how (target-const w :shut-rd))
                         (= how (target-const w :shut-rdwr)))
               peer-fd (:peer-fd s)]
           (if-not (or write? read?)
             (err-pair (native-error w :einval))
             (do
               (swap! (:state w)
                      (fn [cur]
                        (cond-> cur
                          write? (assoc-in [:sockets fd :write-shutdown?] true)
                          (and write? (get-in cur [:sockets peer-fd]))
                          (assoc-in [:sockets peer-fd :peer-write-shutdown?]
                                    true)
                          read? (assoc-in [:sockets fd :read-shutdown?] true))))
               (notify-readiness! w)
               (ok-pair)))))))))

(defn- close-result [w s]
  (let [fd (:fd s)
        peer-fd (:peer-fd s)
        pending (if (= :listening (:state s)) (:accept-q s) [])]
    (doseq [server-fd pending]
      (when-let [server (socket-of w server-fd)]
        (swap! (:state w) update :sockets dissoc server-fd)
        (swap! (:state w) update :closed-fds conj server-fd)
        (when-let [client-fd (:peer-fd server)]
          (when (socket-of w client-fd)
            (swap! (:state w) update-in [:sockets client-fd]
                   assoc :peer-write-shutdown? true :peer-closed? true)))))
    (swap! (:state w) update :sockets dissoc fd)
    (swap! (:state w) update :closed-fds conj fd)
    (when (= :listening (:state s))
      (swap! (:state w) update :listeners dissoc (:local-port s)))
    (when (and peer-fd (socket-of w peer-fd))
      (swap! (:state w) update-in [:sockets peer-fd]
             assoc :peer-write-shutdown? true :peer-closed? true))
    (notify-readiness! w)
    0))

;; ---- pipe (self-pipe wake) handlers ------------------------------------

(defn- h-pipe [w {:keys [arguments]}]
  (let [[pipefd-ptr] (vec arguments)
        rfd (claim-fd! w)
        wfd (claim-fd! w)]
    (invoke-mem w :write [pipefd-ptr :int 0 rfd])
    (invoke-mem w :write [pipefd-ptr :int 4 wfd])
    (swap! (:state w) assoc-in [:pipes rfd]
           {:fd rfd :role :reader :peer-fd wfd
            :nonblocking? false :fifo [] :writer-closed? false})
    (swap! (:state w) assoc-in [:pipes wfd]
           {:fd wfd :role :writer :peer-fd rfd
            :nonblocking? false :reader-closed? false})
    [0 0]))

(defn- pipe-write-result [w s buf-ptr len]
  (let [reader-fd (:peer-fd s)
        reader (pipe-of w reader-fd)]
    (cond
      ;; EPIPE precedence: a closed or missing reader fails before the
      ;; capacity model is consulted.
      (:reader-closed? s)
      (err-pair (target-errno w :epipe))

      (nil? reader)
      (err-pair (target-errno w :epipe))

      ;; A live reader: zero length keeps the current result.
      (zero? len)
      [len 0]

      :else
      (let [capacity (:pipe-capacity (:config w))
            occupied (count (:fifo reader))
            room (- capacity occupied)]
        (if (<= len room)
          ;; The entire positive write fits: copy all bytes and update the
          ;; reader FIFO plus evidence coherently in one state swap so a
          ;; concurrent readiness observer never sees occupancy without its
          ;; matching evidence.
          (let [moved (ba->uvec (invoke-mem w :read-array [buf-ptr len]))
                new-occ (+ occupied len)]
            (swap! (:state w)
                   (fn [cur]
                     (-> cur
                         (update-in [:pipes reader-fd :fifo] into moved)
                         (update :pipe-evidence
                                 (fn [ev]
                                   (assoc ev
                                          :max-pipe-fifo-bytes
                                          (max (:max-pipe-fifo-bytes ev)
                                               new-occ)))))))
            (notify-readiness! w)
            [len 0])
          ;; The write does not fit. A nonblocking writer captures EAGAIN
          ;; without moving bytes; a blocking writer cannot wait inside the
          ;; model and fails closed with a typed transition. Evidence records
          ;; the would-block on both paths.
          (do (swap! (:state w)
                     update :pipe-evidence
                     (fn [ev] (update ev :pipe-would-blocks inc)))
              (if (:nonblocking? s)
                (err-pair (target-errno w :eagain))
                (fail! :jolt.sim.net.posix-loopback/blocking-pipe-write-unsupported
                       "blocking pipe write that does not fit the reader FIFO requires the modeled poll/wait slice"
                       {:fd (:fd s) :reader-fd reader-fd
                        :capacity capacity :length len}))))))))

(defn- h-write [w {:keys [arguments]}]
  (let [[fd buf-ptr len] (vec arguments)]
    (if-let [s (pipe-of w fd)]
      (if (= :writer (:role s))
        (pipe-write-result w s buf-ptr len)
        (err-pair (native-error w :ebadf)))
      (err-pair (native-error w :ebadf)))))

(defn- pipe-read-result [w s buf-ptr len]
  (if (zero? len)
    [0 0]
    (let [q (:fifo s)]
      (if (pos? (count q))
        (let [n (min len (:progress-limit (:config w)) (count q))
              got (subvec q 0 n)]
          (swap! (:state w) assoc-in [:pipes (:fd s) :fifo] (subvec q n))
          (invoke-mem w :write-array [buf-ptr (uvec->byte-array got)])
          (notify-readiness! w)
          [n 0])
        (cond
          (:writer-closed? s) [0 0]
          (:nonblocking? s) (err-pair (target-errno w :eagain))
          :else
          (fail! :jolt.sim.net.posix-loopback/blocking-pipe-read-unsupported
                 "blocking empty pipe reads require the modeled poll/wait slice"
                 {:fd (:fd s)}))))))

(defn- h-read [w {:keys [arguments]}]
  (let [[fd buf-ptr len] (vec arguments)]
    (if-let [s (pipe-of w fd)]
      (if (= :reader (:role s))
        (pipe-read-result w s buf-ptr len)
        (err-pair (native-error w :ebadf)))
      (err-pair (native-error w :ebadf)))))

(defn- close-pipe-endpoint [w s]
  (let [fd (:fd s)
        peer-fd (:peer-fd s)
        mark (case (:role s)
               :writer :writer-closed?
               :reader :reader-closed?
               nil)]
    (swap! (:state w) update :pipes dissoc fd)
    (swap! (:state w) update :closed-fds conj fd)
    (when (and mark (pipe-of w peer-fd))
      (swap! (:state w) assoc-in [:pipes peer-fd mark] true))
    (notify-readiness! w)
    0))

(defn- h-close [w {:keys [arguments]}]
  (let [[fd] (vec arguments)]
    (cond
      (socket-of w fd) (close-result w (socket-of w fd))
      (pipe-of w fd) (close-pipe-endpoint w (pipe-of w fd))
      :else -1)))

(defn- posix-fns [w]
  {"getaddrinfo" (partial h-getaddrinfo w)
   "freeaddrinfo" (partial h-freeaddrinfo w)
   "socket" (partial h-socket w)
   "setsockopt" (partial h-setsockopt w)
   "bind" (partial h-bind w)
   "listen" (partial h-listen w)
   "fcntl" (partial h-fcntl w)
   "getsockname" (partial h-getsockname w)
   "getpeername" (partial h-getpeername w)
   "connect" (partial h-connect w)
   "accept" (partial h-accept w)
   "getsockopt" (partial h-getsockopt w)
   "send" (partial h-send w)
   "recv" (partial h-recv w)
   "shutdown" (partial h-shutdown w)
   "close" (partial h-close w)
   "pipe" (partial h-pipe w)
   "poll" (partial h-poll w)
   "read" (partial h-read w)
   "write" (partial h-write w)})

(defn foreign-handlers
  "Returns only the target-exact POSIX foreign-function handlers. Ordinary
  transitions are serialized on the world lock. poll owns its narrower critical
  sections itself so a parked wait never prevents a writer, connect, shutdown,
  or close from publishing readiness and waking it."
  [w]
  (let [fns (posix-fns w)
        keys (handler-keys (:target w))]
    (into {}
          (map (fn [key]
                 [key
                  (fn posix-handler [descriptor]
                    (if (= "poll" (nth key 1))
                      ((get fns "poll") descriptor)
                      (locking (:lock w)
                        ((get fns (nth key 1)) descriptor))))]))
          keys)))

(defn handlers
  "Returns one complete runtime :ffi-handlers map: all native-memory handlers
  plus the POSIX loopback foreign handlers over that same memory world."
  [w]
  (merge (:memory-handlers w) (foreign-handlers w)))

;; ---- evidence ----------------------------------------------------------

(defn state
  "Returns the current posix-loopback state (next-fd, next-ephemeral, sockets,
  pipes, listeners) for evidence. Socket records carry state, local/peer ports,
  the paired peer fd, the nonblocking flag, the independent write/read half-open
  flags, the recv FIFO, and (for listeners) the pending accept queue."
  [w]
  @(:state w))

(def ^:private socket-fact-keys
  [:fd :state :local-port :peer-port :peer-fd])

(defn socket-facts
  "Returns a stable, bounded fact map for modeled socket ``fd``, or nil when
  no such socket is live.

  Facts are sampled while holding the world's ordinary POSIX transition lock.
  That locked snapshot is the admission linearization point for boundary
  frontends: it cannot observe the intermediate atom states used inside a
  connect, accept, shutdown, or close transition. The lock is released before
  this function returns; the result is evidence, not a lifecycle lease, and a
  later operation may legitimately observe that the socket has since changed
  or closed."
  [w fd]
  (let [w (validate-world w)]
    (locking (:lock w)
      (when-let [socket (socket-of w fd)]
        (select-keys socket socket-fact-keys)))))

(defn pipe-snapshot
  "Stable plain-data summary of every open modeled pipe endpoint, ordered by fd.
  Each entry carries its role, peer fd, nonblocking flag, the byte occupancy of
  its own FIFO field, and the independent half-open flags. Channel capacity and
  available room are reported for both ends against the one shared reader FIFO;
  a writer whose reader has retired reports zero available room because no
  further write can succeed."
  [w]
  (let [capacity (:pipe-capacity (:config w))
        pipes (:pipes @(:state w))]
    (->> pipes
         vals
         (sort-by :fd)
         (mapv (fn [endpoint]
                 (let [shared-occ
                       (if (= :reader (:role endpoint))
                         (count (:fifo endpoint))
                         (let [peer (get pipes (:peer-fd endpoint))]
                           (if peer
                             (count (:fifo peer))
                             capacity)))]
                   {:fd (:fd endpoint)
                    :role (:role endpoint)
                    :peer-fd (:peer-fd endpoint)
                    :nonblocking? (:nonblocking? endpoint)
                    :fifo-bytes (count (:fifo endpoint))
                    :channel-capacity capacity
                    :channel-available (- capacity shared-occ)
                    :writer-closed? (:writer-closed? endpoint)
                    :reader-closed? (:reader-closed? endpoint)}))))))

(defn readiness-snapshot
  "Stable evidence for the modeled readiness boundary. Host promise identities
  are intentionally omitted; only the monotonic epoch and live waiter count are
  trace-safe values."
  [w]
  (let [r @(:readiness w)]
    {:epoch (:epoch r)
     :waiter-count (count (:waiters r))}))

(defn snapshot
  "Stable plain-data summary of every open socket, ordered by fd. Each entry
  carries its state machine value, local/peer loopback ports, the nonblocking
  flag, the two independent half-open flags, the number of bytes pending in its
  recv FIFO, the configured receive capacity, and the remaining room."
  [w]
  (let [capacity (:stream-capacity (:config w))]
    (->> (:sockets @(:state w))
         vals
         (sort-by :fd)
         (mapv (fn [s]
                 (let [occupied (count (:recv-q s))]
                   {:fd (:fd s)
                    :state (:state s)
                    :local-port (:local-port s)
                    :peer-port (:peer-port s)
                    :nonblocking? (:nonblocking? s)
                    :write-shutdown? (:write-shutdown? s)
                    :peer-write-shutdown? (:peer-write-shutdown? s)
                    :peer-closed? (:peer-closed? s)
                    :recv-bytes occupied
                    :recv-capacity capacity
                    :recv-available (- capacity occupied)}))))))

(defn capacity-summary
  "Stable evidence for the per-socket receive FIFO capacity model: the
  configured finite capacity, the count of sends that would block on a full
  FIFO, the count of writes whose progress capacity reduced below
  min(length, progress-limit), and the maximum byte occupancy observed on any
  one connected socket's receive FIFO."
  [w]
  (let [evidence (:capacity-evidence @(:state w))]
    {:stream-capacity (:stream-capacity evidence)
     :stream-would-blocks (:stream-would-blocks evidence)
     :stream-capacity-limited-writes
     (:stream-capacity-limited-writes evidence)
     :max-stream-recv-bytes (:max-stream-recv-bytes evidence)}))

(defn pipe-capacity-summary
  "Stable evidence for the self-pipe FIFO capacity model: the configured finite
  capacity, the count of writes that would block on a full FIFO, and the
  maximum byte occupancy observed on the shared reader FIFO. This is separate
  from the per-socket stream capacity summary."
  [w]
  (let [evidence (:pipe-evidence @(:state w))]
    {:pipe-capacity (:pipe-capacity evidence)
     :pipe-would-blocks (:pipe-would-blocks evidence)
     :max-pipe-fifo-bytes (:max-pipe-fifo-bytes evidence)}))

(defn clean?
  "True when shared memory has no live allocations and every modeled socket and
  pipe endpoint is closed. Native allocations, sockets, and pipes are distinct
  ownership domains; all must be retired."
  [w]
  (and (memory/clean? (:memory w))
       (empty? (:sockets @(:state w)))
       (empty? (:pipes @(:state w)))
       (empty? (:listeners @(:state w)))
       (empty? (:addrinfo-allocations @(:state w)))
       (zero? (:waiter-count (readiness-snapshot w)))))
