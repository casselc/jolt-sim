(ns jolt.sim.posix-loopback-model-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.net.posix-loopback :as posix]))

(def ^:private linux-descriptor
  {:platform :posix
   :socklen-type :uint
   :sin-len? false
   :const {:af-inet 2 :sock-stream 1
           :sol-socket 1 :so-error 4
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           :f-getfl 3 :f-setfl 4 :o-nonblock 2048}
   :errno {:eagain 11 :einprogress 115 :eaddrinuse 98
           :econnrefused 111 :econnreset 104 :epipe 32}
   :layout {:sockaddr-in {:size 16 :family 0 :port 2 :addr 4}
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8
                       :protocol 12 :addrlen 16 :addr 24
                       :canonname 32 :next 40 :addrlen-type :uint}}})

(def ^:private darwin-descriptor
  {:platform :posix
   :socklen-type :uint
   :sin-len? true
   :const {:af-inet 2 :sock-stream 1
           :sol-socket 65535 :so-error 4103
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           :f-getfl 3 :f-setfl 4 :o-nonblock 4}
   :errno {:eagain 35 :einprogress 36 :eaddrinuse 48
           :econnrefused 61 :econnreset 54 :epipe 32}
   :layout {:sockaddr-in {:size 16 :family 1 :port 2 :addr 4}
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

(defn- foreign-key [symbol blocking?]
  (first
   (filter #(and (= symbol (nth % 1)) (= blocking? (nth % 4)))
           posix/handler-keys)))

(defn- foreign [h symbol blocking? arguments]
  (let [key (foreign-key symbol blocking?)]
    ((get (:handlers h) key)
     {:kind :foreign-function
      :task 0
      :symbol symbol
      :argument-types (nth key 2)
      :return-type (nth key 3)
      :blocking? blocking?
      :capture-native-error? (nth key 5)
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
    (is (= 20 (count posix/handler-keys)))
    (is (= (set posix/handler-keys) foreign-keys))
    (is (= 15 (count native-keys)))
    (is (= 35 (count handlers)))
    (is (contains? foreign-keys
                  [:foreign-function "pipe" [:pointer] :int false true]))
    (is (contains? foreign-keys
                  [:foreign-function "read" [:int :pointer :size_t]
                   :ssize_t false true]))
    (is (contains? foreign-keys
                  [:foreign-function "write" [:int :pointer :size_t]
                   :ssize_t false true]))
    (is (= #{true false}
           (set (map #(nth % 4)
                     (filter #(= "connect" (nth % 1))
                             posix/handler-keys)))))))

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
