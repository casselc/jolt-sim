(ns jolt.maelstrom.echo
  "The official Maelstrom Echo workload.

  handle-echo is a plain (node request-envelope) -> reply-body function with
  no transport dependency; jolt.maelstrom.node supplies src, dest, msg_id,
  and in_reply_to, and calls the injected send! with the finished envelope.
  handlers is the map jolt.maelstrom.node/create-node expects.")

(defn- fail!
  [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn handle-echo
  "Builds the echo_ok reply body for one echo request. The request body must
  carry an :echo key -- present with any value, including nil -- or this
  fails closed with a typed ex-info rather than guessing a value."
  [_node {:keys [body]}]
  (when-not (contains? body :msg_id)
    (fail! ::missing-msg-id "echo request body has no msg_id" {:body body}))
  (when-not (contains? body :echo)
    (fail! ::missing-echo "echo request body has no echo key" {:body body}))
  {:type "echo_ok" :echo (:echo body)})

(def handlers
  "The :handlers map jolt.maelstrom.node/create-node expects for the Echo
  workload."
  {"echo" handle-echo})
