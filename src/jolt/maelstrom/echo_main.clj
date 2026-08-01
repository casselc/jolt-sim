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

(defn -main
  [& _args]
  (let [n (node/create-node
           {:handlers echo/handlers
            :send! (json-lines/line-sender json-lines/stdout-write-line!)})]
    (json-lines/pump! json-lines/stdin-read-line! (fn [envelope] (node/handle! n envelope)))
    nil))
