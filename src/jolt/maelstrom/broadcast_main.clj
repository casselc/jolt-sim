(ns jolt.maelstrom.broadcast-main
  "Entry point that runs the official Maelstrom Broadcast workload over real
  stdin/stdout JSON-lines framing.

  Wires exactly one jolt.maelstrom.node instance to jolt.maelstrom.broadcast's
  handlers, injects the production JSON-lines sender for outbound replies, and
  pumps stdin through the node's handle! until EOF. All init, dispatch, reply,
  topology, broadcast, and read behavior is unchanged from the
  transport-agnostic node and Broadcast workload.

  Broadcast adds one delivery-retry obligation that Echo does not have: a
  single background worker retries every outstanding delivery at a fixed
  1000ms cadence. The worker is harmless before topology (the pending set is
  empty, so retry-pending! is a no-op) and is fully independent of stdin
  processing -- it runs on its own future thread and calls the same node's
  retry-pending! on the application slice. On EOF the worker is stopped and
  awaited with a bounded timeout; exceeding that timeout is fatal.

  Any pump, writer, retry, or worker failure emits exactly one bounded
  coordinate-only diagnostic to stderr and terminates nonzero. A worker
  failure terminates even while main is blocked reading stdin, because the
  worker owns its own fail-process! path. External envelopes and handler
  ex-data are never printed."
  (:require [jolt.maelstrom.broadcast :as broadcast]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.json-lines :as json-lines]))

(def ^:private retry-cadence-ms
  "Fixed cadence of the single delivery-retry worker."
  1000)

(def ^:private worker-await-timeout-ms
  "Bounded wait for the retry worker to stop after EOF. Exceeding it is fatal."
  5000)

(defn- diagnostic-coordinate
  [value]
  (cond
    (or (keyword? value) (integer? value)) value
    (string? value) (subs value 0 (min 128 (count value)))
    :else nil))

(defn- fail-process!
  "Writes one bounded coordinate-only diagnostic and terminates. The retained
  request stream is the forensic source of truth; node/handler ex-data can
  contain the complete external envelope and must not be printed by the
  uncaught-exception reporter. The per-process lock makes competing pump and
  worker failures single-writer: the first owner prints and exits while any
  contender remains blocked behind it."
  [failure-lock error]
  (locking failure-lock
    (let [data (ex-data error)
          diagnostic
          (cond-> {:type :jolt.maelstrom.broadcast-main/request-failed}
            (diagnostic-coordinate (:type data))
            (assoc :cause-type (diagnostic-coordinate (:type data)))

            (diagnostic-coordinate (:reason data))
            (assoc :reason (diagnostic-coordinate (:reason data))))]
      (binding [*out* *err*]
        (println (pr-str diagnostic))
        (flush))
      (System/exit 1))))

(defn- start-retry-worker!
  "Starts the single delivery-retry worker on its own future thread. It loops
  while the stop flag is false: sleeps the fixed cadence, then retries every
  outstanding delivery on the supplied node. A retry failure terminates the
  process from this thread, so it is fatal even when main is blocked reading
  stdin. Returns the future so the caller can await it on EOF."
  [failure-lock stop-flag app node]
  (future
    (try
      (loop []
        (when-not @stop-flag
          (Thread/sleep retry-cadence-ms)
          (when-not @stop-flag
            ((:retry-pending! app) node)
            (recur))))
      (catch :default error
        ;; Catch the complete worker body, including sleep/interruption and
        ;; application retries. A worker may fail while main is blocked in
        ;; read-line, so recording an exceptional future is not sufficient.
        (fail-process! failure-lock error)))))

(defn -main
  [& _args]
  (let [failure-lock (Object.)]
    (try
      (let [app (broadcast/create-broadcast)
            n (node/create-node
               {:handlers (:handlers app)
                :on-reply (:on-reply app)
                :send! (json-lines/line-sender json-lines/stdout-write-line!)})
            stop-flag (atom false)
            worker (start-retry-worker! failure-lock stop-flag app n)]
        (json-lines/pump! json-lines/stdin-read-line!
                          (fn [envelope] (node/handle! n envelope))
                          {:body-key-map (:body-key-map app)})
        (reset! stop-flag true)
        (when (= ::timeout
                 (deref worker worker-await-timeout-ms ::timeout))
          (fail-process!
           failure-lock
           (ex-info "retry worker did not stop within the bounded timeout"
                    {:type ::worker-stop-timeout})))
        nil)
      (catch :default error
        (fail-process! failure-lock error)))))
