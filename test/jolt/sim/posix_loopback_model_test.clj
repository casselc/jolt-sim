(ns jolt.sim.posix-loopback-model-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.net.posix-loopback :as posix]))

(def ^:private linux-descriptor
  {:platform :posix
   :socklen-type :uint
   :nfds-type :size_t
   :sin-len? false
   :const {:af-inet 2 :sock-stream 1
           :sol-socket 1 :so-error 4
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           :f-getfl 3 :f-setfl 4 :o-nonblock 2048
           :pollin 1 :pollout 4 :pollerr 8 :pollhup 16 :pollnval 32}
   :errno {:eagain 11 :einprogress 115 :eaddrinuse 98
           :econnrefused 111 :econnreset 104 :epipe 32}
   :layout {:sockaddr-in {:size 16 :family 0 :port 2 :addr 4}
            :pollfd {:size 8 :fd 0 :events 4 :revents 6}
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8
                       :protocol 12 :addrlen 16 :addr 24
                       :canonname 32 :next 40 :addrlen-type :uint}}})

(def ^:private darwin-descriptor
  {:platform :posix
   :socklen-type :uint
   :nfds-type :uint
   :sin-len? true
   :const {:af-inet 2 :sock-stream 1
           :sol-socket 65535 :so-error 4103
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           :f-getfl 3 :f-setfl 4 :o-nonblock 4
           :pollin 1 :pollout 4 :pollerr 8 :pollhup 16 :pollnval 32}
   :errno {:eagain 35 :einprogress 36 :eaddrinuse 48
           :econnrefused 61 :econnreset 54 :epipe 32}
   :layout {:sockaddr-in {:size 16 :family 1 :port 2 :addr 4}
            :pollfd {:size 8 :fd 0 :events 4 :revents 6}
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8
                       :protocol 12 :addrlen 16 :canonname 24
                       :addr 32 :next 40 :addrlen-type :uint}}})

(defn- harness
  ([descriptor]
   (harness descriptor nil))
  ([descriptor config]
   (let [mem (memory/world)
         world (posix/world mem descriptor config)]
     {:memory mem
      :world world
      :handlers (posix/handlers world)})))

(defn- native [h operation & arguments]
  ((get (:handlers h) [:native-operation operation])
   {:kind :native-operation
    :task 0
    :operation operation
    :arguments (vec arguments)}))

(defn- foreign-key [h symbol blocking?]
  (first
   (filter #(and (= symbol (nth % 1)) (= blocking? (nth % 4)))
           (keys (:handlers h)))))

(defn- foreign [h symbol blocking? arguments]
  (let [key (foreign-key h symbol blocking?)]
    ((get (:handlers h) key)
     {:kind :foreign-function
      :task 0
      :symbol symbol
      :argument-types (nth key 2)
      :return-type (nth key 3)
      :blocking? blocking?
      :capture-native-error? (nth key 5)
      :varargs-after (get key 6)
      :arguments (vec arguments)})))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- unsigned [x]
  (bit-and 255 (int x)))

(defn- be16 [v]
  (+ (bit-shift-left (unsigned (nth v 0)) 8)
     (unsigned (nth v 1))))

(defn- sockaddr-bytes [descriptor port]
  (vec
   (concat
    (if (:sin-len? descriptor) [16 2] [2 0])
    [(bit-and (bit-shift-right port 8) 255) (bit-and port 255)]
    [127 0 0 1]
    (repeat 8 0))))

(defn- alloc-sockaddr [h descriptor port]
  (let [ptr (native h :alloc 16)]
    (native h :write-array ptr (byte-array (sockaddr-bytes descriptor port)))
    ptr))

(defn- endpoint-port [h symbol fd]
  (let [address (native h :alloc 16)
        length (native h :alloc 4)]
    (try
      (native h :write length :uint 0 16)
      (is (= [0 0] (foreign h symbol false [fd address length])))
      (be16 (vec (native h :read-array (+ address 2) 2)))
      (finally
        (native h :free length)
        (native h :free address)))))

(defn- open-listener [h descriptor]
  (let [[fd socket-error]
        (foreign h "socket" false [2 1 0])
        address (alloc-sockaddr h descriptor 0)]
    (is (zero? socket-error))
    (is (= [0 0] (foreign h "bind" false [fd address 16])))
    (is (= [0 0] (foreign h "listen" false [fd 8])))
    {:fd fd :address address :port (endpoint-port h "getsockname" fd)}))

(deftest exact-handler-identities-compose-with-one-memory-world
  (let [h (harness linux-descriptor)
        handlers (:handlers h)
        foreign-keys
        (set (filter #(= :foreign-function (first %)) (keys handlers)))
        native-keys
        (set (filter #(= :native-operation (first %)) (keys handlers)))]
    (is (= 21 (count (posix/handler-keys linux-descriptor))))
    (is (= (set (posix/handler-keys linux-descriptor)) foreign-keys))
    (is (= 16 (count native-keys)))
    (is (= 37 (count handlers)))
    (is (contains? foreign-keys
                  [:foreign-function "pipe" [:pointer] :int false true]))
    (is (contains? foreign-keys
                  [:foreign-function "read" [:int :pointer :size_t]
                   :ssize_t false true]))
    (is (contains? foreign-keys
                  [:foreign-function "write" [:int :pointer :size_t]
                   :ssize_t false true]))
    (is (contains? foreign-keys
                  [:foreign-function "poll" [:pointer :size_t :int]
                   :int true true]))
    (is (= #{true false}
           (set (map #(nth % 4)
                     (filter #(= "connect" (nth % 1))
                             (posix/handler-keys linux-descriptor))))))))

(deftest fcntl-handler-key-carries-varargs-after-boundary
  ;; fcntl is variadic: the third argument onward is a variadic position.
  ;; The modeled handler key carries the exact boundary (2) so unchanged
  ;; jolt-net code routes to this handler instead of native fallback.
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (is (contains? (set (posix/handler-keys descriptor))
                   [:foreign-function "fcntl" [:int :int :int]
                    :int false true 2]))
    ;; No other foreign-function key carries a boundary; every other raw key
    ;; remains the six-element shorthand.
    (let [bounded (filter #(and (= :foreign-function (nth % 0 nil))
                                (< 6 (count %)))
                          (posix/handler-keys descriptor))]
      (is (= [[:foreign-function "fcntl" [:int :int :int] :int false true 2]]
             bounded)))))

(deftest unbound-socket-name-is-wildcard-port-zero
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          [fd socket-error] (foreign h "socket" false [2 1 0])
          address (native h :alloc 16)
          length (native h :alloc 4)]
      (try
        (is (zero? socket-error))
        (native h :write length :uint 0 16)
        (is (= [0 0]
               (foreign h "getsockname" false [fd address length])))
        (let [bytes (vec (native h :read-array address 16))]
          (if (:sin-len? descriptor)
            (is (= [16 2] (mapv unsigned (subvec bytes 0 2))))
            (is (= 2 (native h :read address :uint16 0))))
          (is (= [0 0] (mapv unsigned (subvec bytes 2 4))))
          (is (= [0 0 0 0] (mapv unsigned (subvec bytes 4 8))))
          (is (= 16 (native h :read length :uint 0))))
        (finally
          (foreign h "close" false [fd])
          (native h :free length)
          (native h :free address)))
      (is (true? (posix/clean? (:world h)))))))

(deftest resolver-uses-the-supplied-linux-and-darwin-layouts
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          layout (get-in descriptor [:layout :addrinfo])
          node (native h :string->ptr "127.0.0.1")
          service (native h :string->ptr "4321")
          hints (native h :alloc 128)
          result-cell (native h :alloc 8)]
      (native h :write hints :int (:family layout) 2)
      (native h :write hints :int (:socktype layout) 1)
      (native h :write result-cell :pointer 0 0)
      (is (= [0 0]
             (foreign h "getaddrinfo" true
                      [node service hints result-cell])))
      (let [head (native h :read result-cell :pointer 0)
            sockaddr (native h :read head :pointer (:addr layout))
            bytes (vec (native h :read-array sockaddr 16))]
        (is (pos? head))
        (is (= 2 (native h :read head :int (:family layout))))
        (is (= 1 (native h :read head :int (:socktype layout))))
        (is (= 16 (native h :read head :uint (:addrlen layout))))
        (is (zero? (native h :read head :pointer (:next layout))))
        (if (:sin-len? descriptor)
          (do (is (= 16 (unsigned (nth bytes 0))))
              (is (= 2 (unsigned (nth bytes 1)))))
          (is (= 2 (native h :read sockaddr :uint16 0))))
        (is (= 4321 (be16 (subvec bytes 2 4))))
        (is (= [127 0 0 1] (mapv unsigned (subvec bytes 4 8))))
        (is (nil? (foreign h "freeaddrinfo" false [head])))
        (is (= :jolt.sim.net.posix-loopback/unknown-addrinfo
               (:type (ex-data-of
                       #(foreign h "freeaddrinfo" false [head]))))))
      (doseq [ptr [result-cell hints service node]]
        (native h :free ptr))
      (is (true? (posix/clean? (:world h)))))))

(deftest blocking-stream-runs-partial-io-half-close-and-reverse-traffic
  (let [h (harness linux-descriptor)
        listener (open-listener h linux-descriptor)
        listener-fd (:fd listener)
        listener-port (:port listener)
        target (alloc-sockaddr h linux-descriptor listener-port)
        [client _] (foreign h "socket" false [2 1 0])]
    (is (= [-1 11]
           (foreign h "accept" false [listener-fd 0 0])))
    (is (= [0 0]
           (foreign h "connect" true [client target 16])))
    (let [[server accept-error]
          (foreign h "accept" false [listener-fd 0 0])
          client-port (endpoint-port h "getsockname" client)]
      (is (zero? accept-error))
      (is (= listener-port (endpoint-port h "getpeername" client)))
      (is (= listener-port (endpoint-port h "getsockname" server)))
      (is (= client-port (endpoint-port h "getpeername" server)))
      (let [payload (native h :alloc 5)
            received (native h :alloc 5)
            reply (native h :alloc 3)
            reply-received (native h :alloc 3)]
        (native h :write-array payload (byte-array [0 1 2 3 255]))
        (is (= [2 0] (foreign h "send" false [client payload 5 0])))
        (is (= [2 0] (foreign h "send" false [client (+ payload 2) 3 0])))
        (is (= [1 0] (foreign h "send" false [client (+ payload 4) 1 0])))
        (is (= [2 0] (foreign h "recv" false [server received 5 0])))
        (is (= [2 0] (foreign h "recv" false [server (+ received 2) 3 0])))
        (is (= [1 0] (foreign h "recv" false [server (+ received 4) 1 0])))
        (is (= [0 1 2 3 255]
               (mapv unsigned (vec (native h :read-array received 5)))))
        (is (= [0 0] (foreign h "shutdown" false [client 1])))
        (is (= [0 0] (foreign h "recv" false [server received 1 0])))
        (native h :write-array reply (byte-array [42 0 254]))
        (is (= [2 0] (foreign h "send" false [server reply 3 0])))
        (is (= [1 0] (foreign h "send" false [server (+ reply 2) 1 0])))
        (is (= [2 0] (foreign h "recv" false [client reply-received 3 0])))
        (is (= [1 0]
               (foreign h "recv" false [client (+ reply-received 2) 1 0])))
        (is (= [42 0 254]
               (mapv unsigned
                     (vec (native h :read-array reply-received 3)))))
        (doseq [ptr [reply-received reply received payload]]
          (native h :free ptr)))
      (is (= 0 (foreign h "close" false [client])))
      (is (= -1 (foreign h "close" false [client])))
      (is (= 0 (foreign h "close" false [server]))))
    (is (= 0 (foreign h "close" false [listener-fd])))
    (doseq [ptr [target (:address listener)]]
      (native h :free ptr))
    (is (true? (posix/clean? (:world h))))))

(deftest nonblocking-connect-exposes-inprogress-and-so-error-completion
  (let [h (harness darwin-descriptor)
        listener (open-listener h darwin-descriptor)
        target (alloc-sockaddr h darwin-descriptor (:port listener))
        [client _] (foreign h "socket" false [2 1 0])]
    (is (= [2 0] (foreign h "fcntl" false [client 3 0])))
    (is (= [0 0] (foreign h "fcntl" false [client 4 6])))
    (is (= [6 0] (foreign h "fcntl" false [client 3 0])))
    (is (= [-1 36]
           (foreign h "connect" false [client target 16])))
    (let [error-cell (native h :alloc 4)
          length-cell (native h :alloc 4)]
      (native h :write length-cell :uint 0 4)
      (is (= [0 0]
             (foreign h "getsockopt" false
                      [client 65535 4103 error-cell length-cell])))
      (is (zero? (native h :read error-cell :int 0)))
      (is (= 4 (native h :read length-cell :uint 0)))
      (native h :free length-cell)
      (native h :free error-cell))
    (let [[server accept-error]
          (foreign h "accept" false [(:fd listener) 0 0])]
      (is (zero? accept-error))
      (is (= 0 (foreign h "close" false [server]))))
    (is (= 0 (foreign h "close" false [client])))
    (is (= 0 (foreign h "close" false [(:fd listener)])))
    (native h :free target)
    (native h :free (:address listener))
    (is (true? (posix/clean? (:world h))))))

;; ---- self-pipe wake boundary ------------------------------------------

(defn- open-pipe [h]
  (let [cell (native h :alloc 8)]
    (is (= [0 0] (foreign h "pipe" false [cell])))
    (let [rfd (native h :read cell :int 0)
          wfd (native h :read cell :int 4)]
      {:cell cell :reader rfd :writer wfd})))

(defn- make-nonblocking! [h descriptor fd]
  (let [getfl (get-in descriptor [:const :f-getfl])
        setfl (get-in descriptor [:const :f-setfl])
        o-nonblock (get-in descriptor [:const :o-nonblock])
        [flags error] (foreign h "fcntl" false [fd getfl 0])]
    (is (zero? error))
    (is (= [0 0]
           (foreign h "fcntl" false
                    [fd setfl (bit-or flags o-nonblock)])))))

(defn- alloc-poll-entries [h entries]
  (let [buf (native h :alloc (* 8 (count entries)))]
    (doseq [[idx [fd events]] (map-indexed vector entries)]
      (let [entry (+ buf (* idx 8))]
        (native h :write entry :int 0 fd)
        (native h :write entry :int16 4 events)
        (native h :write entry :int16 6 0)))
    buf))

(defn- poll-revents [h buf idx]
  (native h :read (+ buf (* idx 8)) :int16 6))

(defn- wait-for-waiter-count [world expected timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (= expected (:waiter-count (posix/readiness-snapshot world))) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/yield) (recur))))))

(deftest pipe-writes-reader-and-writer-fds-at-offsets-zero-and-four
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          cell (native h :alloc 8)
          [result errno] (foreign h "pipe" false [cell])]
      (is (zero? result))
      (is (zero? errno))
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)]
        (is (= 0x40000000 rfd))
        (is (= (inc rfd) wfd))
        (is (= rfd (native h :read cell :int 0)))
        (is (= wfd (native h :read cell :int 4)))
        (is (empty? (posix/snapshot (:world h))))
        (is (= [[rfd :reader wfd false 0]
                [wfd :writer rfd false 0]]
               (mapv (juxt :fd :role :peer-fd :nonblocking? :fifo-bytes)
                     (posix/pipe-snapshot (:world h)))))
        (foreign h "close" false [rfd])
        (foreign h "close" false [wfd]))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest pipe-endpoints-each-carry-an-independent-fcntl-nonblocking-state
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          getfl (get-in descriptor [:const :f-getfl])
          setfl (get-in descriptor [:const :f-setfl])
          o-nonblock (get-in descriptor [:const :o-nonblock])
          cell (native h :alloc 8)]
      (foreign h "pipe" false [cell])
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)]
        (is (= [0 0] (foreign h "fcntl" false [rfd getfl 0])))
        (is (= [1 0] (foreign h "fcntl" false [wfd getfl 0])))
        (is (= [0 0] (foreign h "fcntl" false [wfd setfl o-nonblock])))
        (is (= [(bit-or 1 o-nonblock) 0]
               (foreign h "fcntl" false [wfd getfl 0])))
        (is (= [0 0] (foreign h "fcntl" false [rfd getfl 0])))
        (is (= [0 0] (foreign h "fcntl" false [rfd setfl o-nonblock])))
        (is (= [o-nonblock 0]
               (foreign h "fcntl" false [rfd getfl 0])))
        (foreign h "close" false [rfd])
        (foreign h "close" false [wfd]))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest pipe-write-copies-all-bytes-and-read-drains-the-shared-fifo-in-slices
  (let [h (harness linux-descriptor)
        cell (native h :alloc 8)]
    (foreign h "pipe" false [cell])
    (let [rfd (native h :read cell :int 0)
          wfd (native h :read cell :int 4)
          payload (native h :alloc 5)
          received (native h :alloc 5)]
      (make-nonblocking! h linux-descriptor rfd)
      (native h :write-array payload (byte-array [10 20 30 40 50]))
      (is (= [5 0] (foreign h "write" false [wfd payload 5])))
      (is (= 5 (:fifo-bytes
                (first (posix/pipe-snapshot (:world h))))))
      (is (= [2 0] (foreign h "read" false [rfd received 5])))
      (is (= [2 0] (foreign h "read" false [rfd (+ received 2) 3])))
      (is (= [1 0] (foreign h "read" false [rfd (+ received 4) 1])))
      (is (= [-1 (get-in linux-descriptor [:errno :eagain])]
             (foreign h "read" false [rfd received 4])))
      (is (= [10 20 30 40 50]
             (mapv unsigned (vec (native h :read-array received 5)))))
      (foreign h "close" false [rfd])
      (foreign h "close" false [wfd])
      (native h :free received)
      (native h :free payload))
    (native h :free cell)
    (is (true? (posix/clean? (:world h))))))

(deftest pipe-empty-read-with-writer-open-would-block
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          cell (native h :alloc 8)]
      (foreign h "pipe" false [cell])
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)
            received (native h :alloc 4)]
        (is (= :jolt.sim.net.posix-loopback/blocking-pipe-read-unsupported
               (:type (ex-data-of
                       #(foreign h "read" false [rfd received 4])))))
        (make-nonblocking! h descriptor rfd)
        (is (= [-1 (get-in descriptor [:errno :eagain])]
               (foreign h "read" false [rfd received 4])))
        (is (= [-1 (get-in descriptor [:errno :eagain])]
               (foreign h "read" false [rfd received 4])))
        (foreign h "close" false [rfd])
        (foreign h "close" false [wfd])
        (native h :free received))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest pipe-read-drains-then-returns-eof-only-after-writer-close
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          cell (native h :alloc 8)]
      (foreign h "pipe" false [cell])
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)
            payload (native h :alloc 3)
            received (native h :alloc 4)]
        (make-nonblocking! h descriptor rfd)
        (is (= [-1 (get-in descriptor [:errno :eagain])]
               (foreign h "read" false [rfd received 4])))
        (native h :write-array payload (byte-array [7 8 9]))
        (is (= [3 0] (foreign h "write" false [wfd payload 3])))
        (is (= 0 (foreign h "close" false [wfd])))
        (is (true? (:writer-closed?
                    (first (posix/pipe-snapshot (:world h))))))
        (is (= [2 0] (foreign h "read" false [rfd received 4])))
        (is (= [1 0] (foreign h "read" false [rfd (+ received 2) 2])))
        (is (= [7 8 9]
               (mapv unsigned (vec (native h :read-array received 3)))))
        (is (= [0 0] (foreign h "read" false [rfd received 4])))
        (is (= [0 0] (foreign h "read" false [rfd received 4])))
        (foreign h "close" false [rfd])
        (native h :free received)
        (native h :free payload))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest pipe-write-returns-epipe-once-the-reader-closes
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          cell (native h :alloc 8)]
      (foreign h "pipe" false [cell])
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)
            payload (native h :alloc 3)]
        (native h :write-array payload (byte-array [1 2 3]))
        (is (= [3 0] (foreign h "write" false [wfd payload 3])))
        (is (= 0 (foreign h "close" false [rfd])))
        (is (= [-1 (get-in descriptor [:errno :epipe])]
               (foreign h "write" false [wfd payload 3])))
        (is (= [-1 (get-in descriptor [:errno :epipe])]
               (foreign h "write" false [wfd payload 3])))
        (foreign h "close" false [wfd])
        (native h :free payload))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest pipe-close-retires-endpoints-idempotently
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          p (open-pipe h)
          rfd (:reader p)
          wfd (:writer p)]
      (is (= 0 (foreign h "close" false [rfd])))
      (is (= -1 (foreign h "close" false [rfd])))
      (is (= 0 (foreign h "close" false [wfd])))
      (is (= -1 (foreign h "close" false [wfd])))
      (is (= -1 (foreign h "close" false [rfd])))
      (native h :free (:cell p))
      (is (true? (posix/clean? (:world h))))
      (is (empty? (:sockets (posix/state (:world h)))))
      (is (empty? (:pipes (posix/state (:world h))))))))

(deftest pipe-resources-stay-leak-free-across-many-pipes
  (let [h (harness linux-descriptor)]
    (dotimes [_ 5]
      (let [p (open-pipe h)]
        (foreign h "close" false [(:reader p)])
        (foreign h "close" false [(:writer p)])
        (native h :free (:cell p))))
    (is (true? (posix/clean? (:world h))))
    (is (empty? (:sockets (posix/state (:world h)))))
    (is (empty? (:pipes (posix/state (:world h)))))
    (is (empty? (:listeners (posix/state (:world h)))))
    (is (empty? (get (posix/state (:world h)) :addrinfo-allocations)))))

;; ---- poll(2) readiness and blocking wake --------------------------------

(deftest poll-handler-identity-is-exactly-target-specific
  (let [linux-keys (set (posix/handler-keys linux-descriptor))
        darwin-keys (set (posix/handler-keys darwin-descriptor))
        linux-poll [:foreign-function "poll" [:pointer :size_t :int]
                    :int true true]
        darwin-poll [:foreign-function "poll" [:pointer :uint :int]
                     :int true true]]
    (is (contains? linux-keys linux-poll))
    (is (not (contains? linux-keys darwin-poll)))
    (is (contains? darwin-keys darwin-poll))
    (is (not (contains? darwin-keys linux-poll)))))

(deftest poll-target-facts-fail-closed
  (doseq [descriptor [(dissoc linux-descriptor :nfds-type)
                      (assoc linux-descriptor :nfds-type :ulong)
                      (assoc-in linux-descriptor [:layout :pollfd :revents] 4)
                      (update linux-descriptor :const dissoc :pollhup)]]
    (is (= :jolt.sim.net.posix-loopback/invalid-target
           (:type (ex-data-of #(posix/world (memory/world) descriptor)))))))

(deftest poll-ignores-negative-slots-and-flags-unknown-positive-fds
  (let [h (harness linux-descriptor)
        pollin (get-in linux-descriptor [:const :pollin])
        pollnval (get-in linux-descriptor [:const :pollnval])
        entries (alloc-poll-entries h [[-1 pollin] [123456 pollin]])]
    (try
      (is (= [1 0] (foreign h "poll" true [entries 2 0])))
      (is (zero? (poll-revents h entries 0)))
      (is (= pollnval (poll-revents h entries 1)))
      (finally (native h :free entries)))
    (is (true? (posix/clean? (:world h))))))

(deftest poll-reports-pipe-data-hangup-error-and-invalid-fd
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor)
          p (open-pipe h)
          rfd (:reader p)
          wfd (:writer p)
          pollin (get-in descriptor [:const :pollin])
          pollout (get-in descriptor [:const :pollout])
          pollerr (get-in descriptor [:const :pollerr])
          pollhup (get-in descriptor [:const :pollhup])
          pollnval (get-in descriptor [:const :pollnval])
          entries (alloc-poll-entries h [[rfd pollin] [wfd pollout]])
          payload (native h :alloc 3)
          received (native h :alloc 3)]
      (try
        (is (= [1 0] (foreign h "poll" true [entries 2 0])))
        (is (zero? (poll-revents h entries 0)))
        (is (= pollout (poll-revents h entries 1)))

        (native h :write-array payload (byte-array [1 2 3]))
        (is (= [3 0] (foreign h "write" false [wfd payload 3])))
        (is (= [2 0] (foreign h "poll" true [entries 2 0])))
        (is (= pollin (poll-revents h entries 0)))
        (is (= pollout (poll-revents h entries 1)))

        (is (= 0 (foreign h "close" false [wfd])))
        (is (= [2 0] (foreign h "poll" true [entries 2 0])))
        (is (= (bit-or pollin pollhup) (poll-revents h entries 0)))
        (is (= pollnval (poll-revents h entries 1)))

        (is (= [2 0] (foreign h "read" false [rfd received 3])))
        (is (= [1 0] (foreign h "read" false [rfd (+ received 2) 1])))
        (is (= [1 0] (foreign h "poll" true [entries 1 0])))
        (is (= pollhup (poll-revents h entries 0)))

        ;; A live writer whose reader retired reports POLLERR without requiring
        ;; it in the interests word; after the writer retires it is POLLNVAL.
        (let [p2 (open-pipe h)
              writer-entry (alloc-poll-entries h [[(:writer p2) pollout]])]
          (try
            (foreign h "close" false [(:reader p2)])
            (is (= [1 0] (foreign h "poll" true [writer-entry 1 0])))
            (is (= (bit-or pollout pollerr)
                   (poll-revents h writer-entry 0)))
            (foreign h "close" false [(:writer p2)])
            (is (= [1 0] (foreign h "poll" true [writer-entry 1 0])))
            (is (= pollnval (poll-revents h writer-entry 0)))
            (finally
              (native h :free writer-entry)
              (native h :free (:cell p2)))))
        (foreign h "close" false [rfd])
        (finally
          (native h :free received)
          (native h :free payload)
          (native h :free entries)
          (native h :free (:cell p))))
      (is (zero? (:waiter-count (posix/readiness-snapshot (:world h)))))
      (is (true? (posix/clean? (:world h)))))))

(deftest poll-reports-listener-stream-data-eof-write-and-hangup
  (let [h (harness linux-descriptor)
        listener (open-listener h linux-descriptor)
        target (alloc-sockaddr h linux-descriptor (:port listener))
        [client _] (foreign h "socket" false [2 1 0])
        pollin (get-in linux-descriptor [:const :pollin])
        pollout (get-in linux-descriptor [:const :pollout])
        pollhup (get-in linux-descriptor [:const :pollhup])
        listener-entry (alloc-poll-entries h [[(:fd listener) pollin]])]
    (try
      (is (= [0 0] (foreign h "poll" true [listener-entry 1 0])))
      (make-nonblocking! h linux-descriptor client)
      (is (= [-1 (get-in linux-descriptor [:errno :einprogress])]
             (foreign h "connect" false [client target 16])))
      (is (= [1 0] (foreign h "poll" true [listener-entry 1 0])))
      (is (= pollin (poll-revents h listener-entry 0)))
      (let [[server accept-error]
            (foreign h "accept" false [(:fd listener) 0 0])
            stream-entries
            (alloc-poll-entries h [[client pollout]
                                   [server (bit-or pollin pollout)]])
            payload (native h :alloc 1)
            received (native h :alloc 1)]
        (try
          (is (zero? accept-error))
          (is (= [2 0] (foreign h "poll" true [stream-entries 2 0])))
          (is (= pollout (poll-revents h stream-entries 0)))
          (is (= pollout (poll-revents h stream-entries 1)))

          (native h :write-array payload (byte-array [42]))
          (is (= [1 0] (foreign h "send" false [client payload 1 0])))
          (is (= [2 0] (foreign h "poll" true [stream-entries 2 0])))
          (is (= (bit-or pollin pollout)
                 (poll-revents h stream-entries 1)))
          (is (= [1 0] (foreign h "recv" false [server received 1 0])))

          (is (= [0 0] (foreign h "shutdown" false [client 1])))
          (is (= [1 0] (foreign h "poll" true [stream-entries 2 0])))
          (is (= (bit-or pollin pollout)
                 (poll-revents h stream-entries 1)))
          (is (= [0 0] (foreign h "recv" false [server received 1 0])))

          (is (= 0 (foreign h "close" false [client])))
          (is (= [2 0] (foreign h "poll" true [stream-entries 2 0])))
          (is (= (bit-or pollin pollout pollhup)
                 (poll-revents h stream-entries 1)))
          (foreign h "close" false [server])
          (finally
            (native h :free received)
            (native h :free payload)
            (native h :free stream-entries))))
      (foreign h "close" false [(:fd listener)])
      (finally
        (native h :free listener-entry)
        (native h :free target)
        (native h :free (:address listener))))
    (is (true? (posix/clean? (:world h))))))

(deftest blocking-poll-releases-world-lock-and-wakes-after-pipe-write
  (let [h (harness linux-descriptor)
        p (open-pipe h)
        pollin (get-in linux-descriptor [:const :pollin])
        entry (alloc-poll-entries h [[(:reader p) pollin]])
        payload (native h :alloc 1)
        waiting (future (foreign h "poll" true [entry 1 2000]))]
    (try
      (is (true? (wait-for-waiter-count (:world h) 1 2000)))
      (native h :write-array payload (byte-array [1]))
      ;; This write must acquire the same world lock the poll snapshot used. If
      ;; poll held it while parked, the test would time out instead of waking.
      (is (= [1 0] (foreign h "write" false [(:writer p) payload 1])))
      (is (= [1 0] (deref waiting 3000 :timeout)))
      (is (= pollin (poll-revents h entry 0)))
      (is (zero? (:waiter-count (posix/readiness-snapshot (:world h)))))
      (is (pos? (:epoch (posix/readiness-snapshot (:world h))))
          "the readiness transition advances before delivering the waiter")
      (finally
        (foreign h "close" false [(:reader p)])
        (foreign h "close" false [(:writer p)])
        (native h :free payload)
        (native h :free entry)
        (native h :free (:cell p))))
    (is (true? (posix/clean? (:world h))))))

(deftest blocking-poll-wakes-on-connect-and-stream-send-transitions
  (let [h (harness linux-descriptor)
        listener (open-listener h linux-descriptor)
        target (alloc-sockaddr h linux-descriptor (:port listener))
        [client _] (foreign h "socket" false [2 1 0])
        pollin (get-in linux-descriptor [:const :pollin])
        listener-entry (alloc-poll-entries h [[(:fd listener) pollin]])
        accept-wait (future (foreign h "poll" true [listener-entry 1 3000]))]
    (try
      (is (true? (wait-for-waiter-count (:world h) 1 2000)))
      (is (= [0 0] (foreign h "connect" true [client target 16])))
      (is (= [1 0] (deref accept-wait 3000 :timeout)))
      (is (= pollin (poll-revents h listener-entry 0)))
      (let [[server accept-error]
            (foreign h "accept" false [(:fd listener) 0 0])
            read-entry (alloc-poll-entries h [[server pollin]])
            payload (native h :alloc 1)
            read-wait (future (foreign h "poll" true [read-entry 1 3000]))]
        (try
          (is (zero? accept-error))
          (is (true? (wait-for-waiter-count (:world h) 1 2000)))
          (native h :write-array payload (byte-array [99]))
          (is (= [1 0] (foreign h "send" false [client payload 1 0])))
          (is (= [1 0] (deref read-wait 3000 :timeout)))
          (is (= pollin (poll-revents h read-entry 0)))
          (foreign h "close" false [server])
          (finally
            (native h :free payload)
            (native h :free read-entry))))
      (foreign h "close" false [client])
      (foreign h "close" false [(:fd listener)])
      (finally
        (native h :free listener-entry)
        (native h :free target)
        (native h :free (:address listener))))
    (is (zero? (:waiter-count (posix/readiness-snapshot (:world h)))))
    (is (true? (posix/clean? (:world h))))))

(deftest blocking-poll-times-out-and-retires-its-waiter
  (let [h (harness darwin-descriptor)
        p (open-pipe h)
        pollin (get-in darwin-descriptor [:const :pollin])
        entry (alloc-poll-entries h [[(:reader p) pollin]])
        waiting
        (future
          (let [started (System/nanoTime)
                result (foreign h "poll" true [entry 1 50])]
            {:result result
             :elapsed-ms (quot (- (System/nanoTime) started) 1000000)}))]
    (try
      (is (true? (wait-for-waiter-count (:world h) 1 2000)))
      (let [outcome (deref waiting 3000 :timeout)]
        (is (= [0 0] (:result outcome)))
        (is (<= 20 (:elapsed-ms outcome))
            "a registered finite wait must not return immediately"))
      (is (zero? (poll-revents h entry 0)))
      (is (zero? (:waiter-count (posix/readiness-snapshot (:world h)))))
      (finally
        (foreign h "close" false [(:reader p)])
        (foreign h "close" false [(:writer p)])
        (native h :free entry)
        (native h :free (:cell p))))
    (is (true? (posix/clean? (:world h))))))

(deftest unrelated-readiness-wakes-recompute-and-repark-other-polls
  (let [h (harness linux-descriptor)
        p1 (open-pipe h)
        p2 (open-pipe h)
        pollin (get-in linux-descriptor [:const :pollin])
        e1 (alloc-poll-entries h [[(:reader p1) pollin]])
        e2 (alloc-poll-entries h [[(:reader p2) pollin]])
        payload (native h :alloc 1)
        w1 (future (foreign h "poll" true [e1 1 3000]))
        w2 (future (foreign h "poll" true [e2 1 3000]))]
    (try
      (is (true? (wait-for-waiter-count (:world h) 2 2000)))
      (native h :write-array payload (byte-array [7]))
      (is (= [1 0] (foreign h "write" false [(:writer p1) payload 1])))
      (is (= [1 0] (deref w1 3000 :timeout)))
      ;; The global publication woke both host waiters. Only the waiter whose
      ;; descriptor is now ready may return; the other must recompute and park
      ;; again rather than trusting the notification cause.
      (is (true? (wait-for-waiter-count (:world h) 1 2000)))
      (is (= [1 0] (foreign h "write" false [(:writer p2) payload 1])))
      (is (= [1 0] (deref w2 3000 :timeout)))
      (is (= pollin (poll-revents h e1 0)))
      (is (= pollin (poll-revents h e2 0)))
      (is (zero? (:waiter-count (posix/readiness-snapshot (:world h)))))
      (finally
        (doseq [fd [(:reader p1) (:writer p1)
                    (:reader p2) (:writer p2)]]
          (foreign h "close" false [fd]))
        (doseq [ptr [payload e1 e2 (:cell p1) (:cell p2)]]
          (native h :free ptr))))
    (is (true? (posix/clean? (:world h))))))

;; ---- per-socket receive FIFO stream capacity ---------------------------

(defn- connected-pair [h descriptor]
  (let [listener (open-listener h descriptor)
        target (alloc-sockaddr h descriptor (:port listener))
        [client _] (foreign h "socket" false [2 1 0])]
    (is (= [0 0] (foreign h "connect" true [client target 16])))
    (let [[server accept-error]
          (foreign h "accept" false [(:fd listener) 0 0])]
      (is (zero? accept-error))
      {:listener listener :target target
       :client client :server server})))

(defn- snap-by-fd [world fd]
  (some #(when (= (:fd %) fd) %) (posix/snapshot world)))

(defn- close-pair [h pair]
  (foreign h "close" false [(:client pair)])
  (foreign h "close" false [(:server pair)])
  (foreign h "close" false [(:fd (:listener pair))])
  (native h :free (:target pair))
  (native h :free (:address (:listener pair))))

(deftest stream-capacity-default-is-finite-and-invalid-values-fail-closed
  (is (= {:stream-capacity 65536
          :stream-would-blocks 0
          :stream-capacity-limited-writes 0
          :max-stream-recv-bytes 0}
         (posix/capacity-summary
          (posix/world (memory/world) linux-descriptor))))
  (is (= {:stream-capacity 3
          :stream-would-blocks 0
          :stream-capacity-limited-writes 0
          :max-stream-recv-bytes 0}
         (posix/capacity-summary
          (posix/world (memory/world) linux-descriptor
                       {:stream-capacity 3}))))
  (doseq [bad [0 -1 nil "3" 3.0]]
    (is (= :jolt.sim.net.posix-loopback/invalid-config
           (:type (ex-data-of
                   #(posix/world (memory/world) linux-descriptor
                                 {:stream-capacity bad}))))
        (str "capacity " (pr-str bad) " must be rejected"))))

(deftest stream-capacity-limits-nonblocking-sends-and-poll-readiness
  (let [h (harness linux-descriptor {:stream-capacity 3 :progress-limit 2})
        pair (connected-pair h linux-descriptor)
        client (:client pair)
        server (:server pair)
        pollout (get-in linux-descriptor [:const :pollout])
        eagain (get-in linux-descriptor [:errno :eagain])
        payload (native h :alloc 5)
        received (native h :alloc 5)
        entries (alloc-poll-entries h [[client pollout]])]
    (try
      (native h :write-array payload (byte-array [0 1 2 3 4]))
      (make-nonblocking! h linux-descriptor client)
      ;; The client is writable while room == capacity.
      (is (= [1 0] (foreign h "poll" true [entries 1 0])))
      (is (= pollout (poll-revents h entries 0)))
      ;; Send 1: progress-limit 2 caps progress, room 3 allows both bytes.
      (is (= [2 0] (foreign h "send" false [client payload 5 0])))
      (is (= 2 (:recv-bytes (snap-by-fd (:world h) server))))
      (is (= 3 (:recv-capacity (snap-by-fd (:world h) server))))
      (is (= 1 (:recv-available (snap-by-fd (:world h) server))))
      ;; Still writable: one byte of room remains.
      (is (= [1 0] (foreign h "poll" true [entries 1 0])))
      (is (= pollout (poll-revents h entries 0)))
      ;; Send 2: room 1 reduces progress below min(3, 2) = 2.
      (is (= [1 0] (foreign h "send" false [client (+ payload 2) 3 0])))
      (is (= 3 (:recv-bytes (snap-by-fd (:world h) server)))
          "the receive queue never exceeds capacity")
      (is (= 0 (:recv-available (snap-by-fd (:world h) server))))
      ;; POLLOUT clears once the peer FIFO is full.
      (is (= [0 0] (foreign h "poll" true [entries 1 0])))
      (is (zero? (poll-revents h entries 0)))
      ;; Send 3: full FIFO, nonblocking descriptor captures EAGAIN.
      (is (= [-1 eagain] (foreign h "send" false [client payload 1 0])))
      (is (= 3 (:recv-bytes (snap-by-fd (:world h) server)))
          "a would-block leaves the queue at capacity")
      ;; A recv on the server publishes readiness and frees one byte of room.
      (is (= [1 0] (foreign h "recv" false [server received 1 0])))
      (is (= 2 (:recv-bytes (snap-by-fd (:world h) server))))
      (is (= 1 (:recv-available (snap-by-fd (:world h) server))))
      ;; POLLOUT reappears once capacity returns.
      (is (= [1 0] (foreign h "poll" true [entries 1 0])))
      (is (= pollout (poll-revents h entries 0)))
      ;; Capacity evidence is exact after this transition sequence.
      (is (= {:stream-capacity 3
              :stream-would-blocks 1
              :stream-capacity-limited-writes 1
              :max-stream-recv-bytes 3}
             (posix/capacity-summary (:world h))))
      (finally
        (native h :free payload)
        (native h :free received)
        (native h :free entries)
        (close-pair h pair)))
    (is (true? (posix/clean? (:world h))))))

(deftest blocking-stream-send-into-full-fifo-fails-closed
  (let [h (harness linux-descriptor {:stream-capacity 1})
        pair (connected-pair h linux-descriptor)
        client (:client pair)
        payload (native h :alloc 1)]
    (try
      ;; The client is blocking by default. Fill the single-byte FIFO.
      (is (= [1 0] (foreign h "send" false [client payload 1 0])))
      ;; A blocking send into the now-full FIFO cannot wait inside the model
      ;; and fails closed with the typed transition.
      (let [data (ex-data-of #(foreign h "send" false [client payload 1 0]))]
        (is (= :jolt.sim.net.posix-loopback/blocking-stream-send-unsupported
               (:type data)))
        (is (= 1 (:capacity data))))
      ;; Evidence records the would-block even on the closed failure path.
      (is (= 1 (:stream-would-blocks
                (posix/capacity-summary (:world h)))))
      (finally
        (native h :free payload)
        (close-pair h pair)))
    (is (true? (posix/clean? (:world h))))))

(deftest parked-write-poll-wakes-when-recv-releases-capacity
  (let [h (harness linux-descriptor {:stream-capacity 1 :progress-limit 2})
        pair (connected-pair h linux-descriptor)
        client (:client pair)
        server (:server pair)
        pollout (get-in linux-descriptor [:const :pollout])
        payload (native h :alloc 1)
        received (native h :alloc 1)
        entry (alloc-poll-entries h [[client pollout]])]
    (try
      (make-nonblocking! h linux-descriptor client)
      ;; Fill the single-byte FIFO; POLLOUT then clears.
      (is (= [1 0] (foreign h "send" false [client payload 1 0])))
      (is (= [0 0] (foreign h "poll" true [entry 1 0])))
      (is (zero? (poll-revents h entry 0)))
      ;; Park a blocking poll for write readiness on the full socket.
      (let [waiting (future (foreign h "poll" true [entry 1 3000]))]
        (is (true? (wait-for-waiter-count (:world h) 1 2000)))
        ;; A recv on the server publishes readiness and must wake the parked
        ;; writer even though it was already parked while capacity was zero.
        (is (= [1 0] (foreign h "recv" false [server received 1 0])))
        (is (= [1 0] (deref waiting 3000 :timeout)))
        (is (= pollout (poll-revents h entry 0))))
      (finally
        (native h :free payload)
        (native h :free received)
        (native h :free entry)
        (close-pair h pair)))
    (is (true? (posix/clean? (:world h))))))

(deftest epipe-precedence-beats-stream-capacity
  (let [h (harness linux-descriptor {:stream-capacity 1})
        shut-rd (get-in linux-descriptor [:const :shut-rd])
        epipe (get-in linux-descriptor [:errno :epipe])]
    ;; Case 1: peer read-shutdown. Fill the FIFO, then the peer stops reading.
    (let [pair (connected-pair h linux-descriptor)
          client (:client pair)
          server (:server pair)
          payload (native h :alloc 1)]
      (try
        (make-nonblocking! h linux-descriptor client)
        (is (= [1 0] (foreign h "send" false [client payload 1 0])))
        (is (= [0 0] (foreign h "shutdown" false [server shut-rd])))
        (is (= [-1 epipe]
               (foreign h "send" false [client payload 1 0]))
            "EPIPE beats capacity when the peer read half is shut down")
        (finally
          (native h :free payload)
          (close-pair h pair))))
    ;; Case 2: peer gone (closed). Fill the FIFO, then the peer retires.
    (let [pair (connected-pair h linux-descriptor)
          client (:client pair)
          server (:server pair)
          payload (native h :alloc 1)]
      (try
        (make-nonblocking! h linux-descriptor client)
        (is (= [1 0] (foreign h "send" false [client payload 1 0])))
        (is (= 0 (foreign h "close" false [server])))
        (is (= [-1 epipe]
               (foreign h "send" false [client payload 1 0]))
            "EPIPE beats capacity when the peer is gone")
        (finally
          (native h :free payload)
          (close-pair h pair))))
    (is (true? (posix/clean? (:world h))))))

;; ---- self-pipe FIFO capacity -------------------------------------------

(defn- pipe-by-role [world role]
  (some #(when (= role (:role %)) %) (posix/pipe-snapshot world)))

(deftest pipe-capacity-default-is-finite-and-invalid-values-fail-closed
  (is (= {:pipe-capacity 65536
          :pipe-would-blocks 0
          :max-pipe-fifo-bytes 0}
         (posix/pipe-capacity-summary
          (posix/world (memory/world) linux-descriptor))))
  (is (= {:pipe-capacity 4
          :pipe-would-blocks 0
          :max-pipe-fifo-bytes 0}
         (posix/pipe-capacity-summary
          (posix/world (memory/world) darwin-descriptor
                       {:pipe-capacity 4}))))
  (doseq [bad [0 -1 nil "3" 3.0]]
    (is (= :jolt.sim.net.posix-loopback/invalid-config
           (:type (ex-data-of
                   #(posix/world (memory/world) linux-descriptor
                                 {:pipe-capacity bad}))))
        (str "pipe-capacity " (pr-str bad) " must be rejected"))))

(deftest pipe-capacity-limits-nonblocking-writes-poll-and-evidence
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor {:pipe-capacity 3})
          cell (native h :alloc 8)]
      (foreign h "pipe" false [cell])
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)
            pollout (get-in descriptor [:const :pollout])
            eagain (get-in descriptor [:errno :eagain])
            payload (native h :alloc 4)
            received (native h :alloc 3)
            entries (alloc-poll-entries h [[wfd pollout]])]
        (try
          (make-nonblocking! h descriptor wfd)
          (native h :write-array payload (byte-array [0 1 2 3]))
          ;; The writer is writable while the reader FIFO has room, and the
          ;; snapshot reports the configured capacity against both ends.
          (is (= [1 0] (foreign h "poll" true [entries 1 0])))
          (is (= pollout (poll-revents h entries 0)))
          (is (= 3 (:channel-capacity (pipe-by-role (:world h) :reader))))
          (is (= 3 (:channel-capacity (pipe-by-role (:world h) :writer))))
          (is (= 3 (:channel-available (pipe-by-role (:world h) :reader))))
          (is (= 3 (:channel-available (pipe-by-role (:world h) :writer))))
          ;; A positive write that fits copies all bytes into the reader FIFO.
          (is (= [3 0] (foreign h "write" false [wfd payload 3])))
          (is (= 3 (:fifo-bytes (pipe-by-role (:world h) :reader))))
          (is (= 0 (:channel-available (pipe-by-role (:world h) :writer)))
              "the writer end shares the reader FIFO's room")
          ;; POLLOUT clears once the reader FIFO is full.
          (is (= [0 0] (foreign h "poll" true [entries 1 0])))
          (is (zero? (poll-revents h entries 0)))
          ;; Zero length remains a successful no-op for a live reader even
          ;; when the FIFO is full.
          (is (= [0 0] (foreign h "write" false [wfd payload 0])))
          ;; A write that does not fit captures EAGAIN and moves no bytes.
          (is (= [-1 eagain] (foreign h "write" false [wfd payload 1])))
          (is (= 3 (:fifo-bytes (pipe-by-role (:world h) :reader)))
              "a would-block leaves the FIFO at capacity with no overflow")
          ;; A read on the reader publishes readiness and frees one byte.
          (is (= [1 0] (foreign h "read" false [rfd received 1])))
          (is (= 0 (unsigned (first (vec (native h :read-array received 1))))))
          ;; POLLOUT reappears once capacity returns.
          (is (= [1 0] (foreign h "poll" true [entries 1 0])))
          (is (= pollout (poll-revents h entries 0)))
          ;; The remaining bytes retain their exact order.
          (is (= [2 0] (foreign h "read" false [rfd (+ received 1) 2])))
          (is (= [0 1 2]
                 (mapv unsigned (vec (native h :read-array received 3)))))
          ;; Exact pipe-capacity evidence after this transition sequence.
          (is (= {:pipe-capacity 3
                  :pipe-would-blocks 1
                  :max-pipe-fifo-bytes 3}
                 (posix/pipe-capacity-summary (:world h))))
          (finally
            (foreign h "close" false [rfd])
            (foreign h "close" false [wfd])
            (native h :free received)
            (native h :free payload)
            (native h :free entries))))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest blocking-pipe-write-into-full-fifo-fails-closed
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor {:pipe-capacity 1})
          cell (native h :alloc 8)]
      (foreign h "pipe" false [cell])
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)
            payload (native h :alloc 1)]
        (try
          (native h :write-array payload (byte-array [42]))
          ;; The writer is blocking by default. Fill the single-byte FIFO.
          (is (= [1 0] (foreign h "write" false [wfd payload 1])))
          ;; A blocking write that does not fit cannot wait inside the model
          ;; and fails closed with the typed transition and exact evidence.
          (let [data (ex-data-of #(foreign h "write" false [wfd payload 1]))]
            (is (= :jolt.sim.net.posix-loopback/blocking-pipe-write-unsupported
                   (:type data)))
            (is (= 1 (:capacity data)))
            (is (= 1 (:length data))))
          ;; Evidence records the would-block on the closed failure path too.
          (is (= 1 (:pipe-would-blocks
                    (posix/pipe-capacity-summary (:world h)))))
          (is (= 1 (:max-pipe-fifo-bytes
                    (posix/pipe-capacity-summary (:world h)))))
          (finally
            (foreign h "close" false [rfd])
            (foreign h "close" false [wfd])
            (native h :free payload))))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest pipe-capacity-epipe-precedence-beats-would-block
  (doseq [descriptor [linux-descriptor darwin-descriptor]]
    (let [h (harness descriptor {:pipe-capacity 1})
          cell (native h :alloc 8)]
      (foreign h "pipe" false [cell])
      (let [rfd (native h :read cell :int 0)
            wfd (native h :read cell :int 4)
            epipe (get-in descriptor [:errno :epipe])
            pollout (get-in descriptor [:const :pollout])
            pollerr (get-in descriptor [:const :pollerr])
            payload (native h :alloc 1)
            entry (alloc-poll-entries h [[wfd pollout]])]
        (try
          (native h :write-array payload (byte-array [9]))
          ;; Fill the single-byte FIFO at the capacity bound.
          (is (= [1 0] (foreign h "write" false [wfd payload 1])))
          ;; Close the reader; a subsequent write reveals EPIPE rather than
          ;; the capacity would-block path, and the closed reader still
          ;; exposes POLLOUT alongside POLLERR so the write can observe it.
          (is (= 0 (foreign h "close" false [rfd])))
          (is (= [-1 epipe]
                 (foreign h "write" false [wfd payload 0]))
              "terminal EPIPE also precedes the live-reader zero-length case")
          (is (= [-1 epipe]
                 (foreign h "write" false [wfd payload 1]))
              "EPIPE beats capacity once the reader is gone")
          (is (= [1 0] (foreign h "poll" true [entry 1 0])))
          (is (= (bit-or pollout pollerr) (poll-revents h entry 0))
              "terminal readiness preserves POLLOUT and adds POLLERR")
          ;; The would-block counter must not advance on the EPIPE path.
          (is (zero? (:pipe-would-blocks
                      (posix/pipe-capacity-summary (:world h)))))
          (finally
            (foreign h "close" false [wfd])
            (native h :free payload)
            (native h :free entry))))
      (native h :free cell)
      (is (true? (posix/clean? (:world h)))))))

(deftest parked-pipe-write-poll-wakes-when-read-releases-capacity
  (let [h (harness linux-descriptor {:pipe-capacity 1})
        cell (native h :alloc 8)]
    (foreign h "pipe" false [cell])
    (let [rfd (native h :read cell :int 0)
          wfd (native h :read cell :int 4)
          pollout (get-in linux-descriptor [:const :pollout])
          payload (native h :alloc 1)
          received (native h :alloc 1)
          entry (alloc-poll-entries h [[wfd pollout]])
          poll-future (atom nil)]
      (try
        (make-nonblocking! h linux-descriptor wfd)
        ;; Fill the single-byte FIFO; POLLOUT then clears.
        (is (= [1 0] (foreign h "write" false [wfd payload 1])))
        (is (= [0 0] (foreign h "poll" true [entry 1 0])))
        (is (zero? (poll-revents h entry 0)))
        ;; Park a blocking poll for write readiness on the full pipe.
        (let [waiting (future (foreign h "poll" true [entry 1 3000]))]
          (reset! poll-future waiting)
          (is (true? (wait-for-waiter-count (:world h) 1 2000)))
          ;; A read on the reader publishes readiness and must wake the
          ;; parked writer even though it parked while capacity was zero.
          (is (= [1 0] (foreign h "read" false [rfd received 1])))
          (is (= [1 0] (deref waiting 3000 :timeout)))
          (is (= pollout (poll-revents h entry 0))))
        (finally
          (foreign h "close" false [rfd])
          (foreign h "close" false [wfd])
          (native h :free received)
          (native h :free payload)
          ;; If the primary assertion timed out, terminal close above must wake
          ;; and join the poll before its native entry is released.
          (if-let [waiting @poll-future]
            (let [joined (deref waiting 3000 :timeout)]
              (is (not= :timeout joined)
                  "a timed-out poll must join after terminal readiness")
              (when-not (= :timeout joined)
                (native h :free entry)))
            (native h :free entry))
          (native h :free cell)))
      (is (true? (posix/clean? (:world h)))))))
