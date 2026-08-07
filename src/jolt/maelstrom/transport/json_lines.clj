(ns jolt.maelstrom.transport.json-lines
  "Real Maelstrom JSON-lines framing over an injected line reader/writer.

  This is pure boundary code: it knows how to turn one line of text into one
  Maelstrom envelope and back, and how to pump a sequence of lines into a
  handler. It knows nothing about init, dispatch, reply-building, or Echo --
  those stay entirely in jolt.maelstrom.node and whichever workload's
  handlers are wired up by the caller.

  decode-line and encode-line only ever touch two levels of keys: the outer
  envelope (:src, :dest, :body, ...) and the immediate keys of :body
  (:type, :msg_id, :echo, ...). Everything nested inside a body field's
  value is passed through exactly as clojure.data.json produced or expects
  it, so a workload's own payload shapes round-trip untouched.

  jolt.time is required before clojure.data.json: Jolt externalizes
  java.time, and clojure.data.json must see that shim already loaded."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as string]))

(defn- fail!
  [type message cause data]
  (throw (ex-info message (assoc data :type type) cause)))

(defn- text-summary
  "Bounded diagnostics for externally supplied text. The complete input
  belongs in the caller-owned forensic artifact, not exception data."
  [text]
  {:input-chars (count text)
   :input-prefix (subs text 0 (min 128 (count text)))})

(defn- keywordize-keys-shallow
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} m))

(defn- reject-non-whitespace-extra
  [value reader]
  (let [remaining (slurp reader)]
    (if (string/blank? remaining)
      value
      (throw
       (ex-info
        "JSON-lines input contains data after its first JSON value"
        (assoc (text-summary remaining) :reason :trailing-data))))))

(defn decode-line
  "Parses one line of JSON text into a Maelstrom envelope map.

  Converts only the outer envelope keys and the immediate keys of :body to
  keywords; any map or value nested inside a body field is returned exactly
  as parsed, with its own keys left as strings.

  Fails closed with a ::decode-failed ex-info -- preserving the original
  cause -- when the line is not valid JSON, or when it does not decode to a
  JSON object."
  [line]
  (when-not (string? line)
    (fail! ::decode-failed
           "JSON-lines input must be a string"
           nil
           {:reason :not-a-string
            :value-class (str (class line))}))
  (let [parsed (try
                 (json/read-str line :extra-data-fn reject-non-whitespace-extra)
                 (catch :default e
                   (fail! ::decode-failed
                          "failed to parse JSON-lines input"
                          e
                          (text-summary line))))]
    (when-not (map? parsed)
      (fail! ::decode-failed
             "JSON-lines input must decode to a JSON object"
             nil
             (assoc (text-summary line) :reason :not-an-object)))
    (reduce-kv
     (fn [envelope k v]
       (assoc envelope (keyword k)
              (if (and (= k "body") (map? v))
                (keywordize-keys-shallow v)
                v)))
     {}
     parsed)))

(defn encode-line
  "Serializes one outbound envelope map to a single JSON string. Appends no
  delimiter of any kind -- callers own line framing.

  Fails closed with a ::encode-failed ex-info -- preserving the original
  cause -- when the envelope cannot be serialized."
  [envelope]
  (when-not (map? envelope)
    (fail! ::encode-failed
           "JSON-lines output must be a Maelstrom envelope object"
           nil
           {:reason :not-an-object
            :value-class (str (class envelope))}))
  (try
    (json/write-str envelope)
    (catch :default e
      (fail! ::encode-failed
             "failed to encode JSON-lines envelope"
             e
             {:reason :codec-failure
              :key-count (count envelope)}))))

(defn line-sender
  "Returns a node send! function. Each outbound envelope is encoded with
  encode-line and written exactly once via the injected one-argument
  write-line! function. write-line! failures propagate unchanged."
  [write-line!]
  (when-not (fn? write-line!)
    (fail! ::invalid-config
           "line-sender requires a one-argument writer function"
           nil
           {:reason :writer-not-a-function
            :value-class (str (class write-line!))}))
  (let [write-lock (Object.)]
    (fn [envelope]
      (let [line (encode-line envelope)]
        ;; A node can complete several handlers concurrently. Keep the
        ;; writer's complete line operation single-owner so print/newline/flush
        ;; implementations cannot interleave fragments on stdout.
        (locking write-lock
          (write-line! line))))))

(defn pump!
  "Reads lines sequentially from the injected zero-argument read-line! until
  it returns nil (EOF). Each nonnil line is decoded with decode-line and
  passed to the injected one-argument handle! function exactly once, in
  order. decode-line and handle! failures propagate unchanged to the
  caller. Returns nil."
  [read-line! handle!]
  (when-not (fn? read-line!)
    (fail! ::invalid-config
           "pump! requires a zero-argument reader function"
           nil
           {:reason :reader-not-a-function
            :value-class (str (class read-line!))}))
  (when-not (fn? handle!)
    (fail! ::invalid-config
           "pump! requires a one-argument handler function"
           nil
           {:reason :handler-not-a-function
            :value-class (str (class handle!))}))
  (loop []
    (when-let [line (read-line!)]
      (handle! (decode-line line))
      (recur)))
  nil)

(defn stdin-read-line!
  "Production read-line! for pump!: reads one line from stdin, or nil at
  EOF."
  []
  (read-line))

(defn stdout-write-line!
  "Production write-line! for line-sender: writes one JSON string to stdout,
  followed by exactly one newline, then flushes immediately. Writes nothing
  else to stdout."
  [line]
  (print line)
  (newline)
  (flush))
