(ns jolt.maelstrom.echo-main
  "Entry point that runs the official Maelstrom Echo workload over real
  stdin/stdout JSON-lines framing.

  Wires exactly one jolt.maelstrom.node instance to jolt.maelstrom.echo's
  handlers, injects the production JSON-lines sender for outbound replies,
  and pumps stdin through the node's handle! until EOF. All init, dispatch,
  reply, and echo behavior is unchanged from the transport-agnostic node and
  Echo workload."
  (:require [jolt.maelstrom.echo :as echo]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.json-lines :as json-lines]))

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
  uncaught-exception reporter."
  [error]
  (let [data (ex-data error)
        diagnostic
        (cond-> {:type :jolt.maelstrom.echo-main/request-failed}
          (diagnostic-coordinate (:type data))
          (assoc :cause-type (diagnostic-coordinate (:type data)))

          (diagnostic-coordinate (:reason data))
          (assoc :reason (diagnostic-coordinate (:reason data))))]
    (binding [*out* *err*]
      (println (pr-str diagnostic))
      (flush))
    (System/exit 1)))

(defn -main
  [& _args]
  (let [n (node/create-node
           {:handlers echo/handlers
            :send! (json-lines/line-sender json-lines/stdout-write-line!)})]
    (try
      (json-lines/pump! json-lines/stdin-read-line!
                        (fn [envelope] (node/handle! n envelope)))
      nil
      (catch :default error
        (fail-process! error)))))
