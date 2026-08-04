(ns jolt.maelstrom.fixtures.echo-scenario
  "One input-capable defsim scenario that drives the unchanged
  jolt.maelstrom.node / jolt.maelstrom.echo Echo workload over the in-memory
  transport in jolt.maelstrom.transport.memory.

  The scenario body only builds official init and echo client request
  envelopes, feeds whatever it takes off the node endpoint to the unchanged
  node handle!, and drains the client replies the node produced. It does not
  reproduce init, Echo, or reply semantics itself -- those live in node and
  echo. defsim only installs the controller around the ordinary body; input is
  the echo payload carried through the round trip."
  (:require [jolt.maelstrom.echo :as echo]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.memory :as memory]
            [jolt.sim.runtime :as rt]))

(def ^:private node-id "n1")

(def ^:private client-id "c1")

(rt/defsim echo-roundtrip [input]
  {}
  (let [transport (memory/create-transport)
        node (node/create-node
              {:handlers echo/handlers
               :send! (memory/send-fn transport)})]
    ;; Official Maelstrom client request messages into the node endpoint.
    (memory/enqueue! transport
                     {:src client-id :dest node-id
                      :body {:type "init" :msg_id 1
                             :node_id node-id :node_ids [node-id]}})
    (memory/enqueue! transport
                     {:src client-id :dest node-id
                      :body {:type "echo" :msg_id 2 :echo input}})
    ;; Drive the node endpoint to empty through the unchanged handler. The node
    ;; answers init itself and dispatches echo to echo/handle-echo; each reply
    ;; it builds lands on the client endpoint through the injected send fn.
    (loop []
      (when-some [inbound (memory/take! transport node-id)]
        (node/handle! node inbound)
        (recur)))
    ;; Drain the replies the node produced, in send order, then retain the
    ;; transport-local history for the later semantic-evidence adapter.
    (let [replies (memory/drain! transport client-id)]
      {:node-id (node/node-id node)
       :replies replies
       :transport (memory/snapshot transport)})))
