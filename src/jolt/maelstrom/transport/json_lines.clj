(ns jolt.maelstrom.transport.json-lines
  "Real Maelstrom JSON-lines framing over an injected line reader/writer.

  This is pure boundary code: it knows how to turn one line of text into one
  Maelstrom envelope and back, and how to pump a sequence of lines into a
  handler. It knows nothing about init, dispatch, reply-building, or Echo --
  those stay entirely in jolt.maelstrom.node and whichever workload's
  handlers are wired up by the caller.

  decode-line and encode-line only ever touch two levels of keys. A closed
  set of node/Echo wire names becomes keywords; unknown application-owned
  names remain strings rather than being interned. Everything nested inside
  a body field's value is passed through exactly as clojure.data.json produced
  or expects it, so a workload's own payload shapes round-trip untouched.

  jolt.time is required before clojure.data.json: Jolt externalizes
  java.time, and clojure.data.json must see that shim already loaded."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as string]))

(def default-max-line-chars
  "Default maximum JSON-lines frame size accepted before JSON parsing. The
  current Jolt stdin primitive returns a complete line, so this bounds codec,
  normalization, and diagnostic work but cannot prevent the host reader from
  first allocating that line. A future bounded-reader runtime seam can close
  that remaining acquisition boundary without changing this API."
  1048576)

(def default-max-json-depth
  "Maximum object/array nesting accepted before JSON parsing."
  128)

(def ^:private outer-wire-keys
  {"src" :src
   "dest" :dest
   "body" :body})

(def ^:private body-wire-keys
  ;; Only names that the transport-neutral node or the currently supplied
  ;; Echo workload consumes become keywords. Unknown workload fields remain
  ;; strings rather than permanently interning attacker-controlled names.
  {"type" :type
   "msg_id" :msg_id
   "in_reply_to" :in_reply_to
   "node_id" :node_id
   "node_ids" :node_ids
   "echo" :echo})

(defn- valid-body-key-map? [value]
  (and (map? value)
       (every? (fn [[wire-name body-key]]
                 (and (string? wire-name)
                      (not (string/blank? wire-name))
                      (keyword? body-key)))
               value)))

(defn- fail!
  [type message cause data]
  (throw (ex-info message (assoc data :type type) cause)))

(defn- text-summary
  "Bounded diagnostics for externally supplied text. The complete input
  belongs in the caller-owned forensic artifact, not exception data."
  [text]
  {:input-chars (count text)
   :input-prefix (subs text 0 (min 128 (count text)))})

(defn- normalize-known-keys
  [key-map m]
  (reduce-kv (fn [acc k v] (assoc acc (get key-map k k) v)) {} m))

(defn- validate-json-shape!
  [line max-line-chars max-json-depth]
  (when (> (count line) max-line-chars)
    (fail! ::decode-failed
           "JSON-lines input exceeds the configured frame limit"
           nil
           (assoc (text-summary line)
                  :reason :frame-too-large
                  :max-line-chars max-line-chars)))
  ;; Scan string/escape state so braces inside JSON strings do not affect the
  ;; structural depth bound. Syntax remains the JSON codec's responsibility.
  (loop [index 0 depth 0 in-string? false escaped? false]
    (when (< index (count line))
      (let [ch (nth line index)]
        (cond
          escaped?
          (recur (inc index) depth in-string? false)

          (and in-string? (= ch \\))
          (recur (inc index) depth true true)

          (= ch \")
          (recur (inc index) depth (not in-string?) false)

          (and (not in-string?) (or (= ch \{) (= ch \[)))
          (let [next-depth (inc depth)]
            (when (> next-depth max-json-depth)
              (fail! ::decode-failed
                     "JSON-lines input exceeds the configured nesting limit"
                     nil
                     (assoc (text-summary line)
                            :reason :nesting-too-deep
                            :max-json-depth max-json-depth)))
            (recur (inc index) next-depth false false))

          (and (not in-string?) (or (= ch \}) (= ch \])))
          (recur (inc index) (max 0 (dec depth)) false false)

          :else
          (recur (inc index) depth in-string? false)))))
  line)

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

  Converts only the closed node/Echo key vocabulary at the outer envelope and
  immediate :body levels to keywords. Unknown property names and any map or
  value nested inside a body field are returned with their keys left as
  strings. A workload may supply `:body-key-map` in the options map to add a
  finite string-to-existing-keyword vocabulary without interning wire input;
  the same options map may be passed as pump!'s third argument.

  Fails closed with a ::decode-failed ex-info -- preserving the original
  codec cause -- when the line exceeds its codec/depth limits, is not valid
  JSON, or does not decode to a JSON object. The current Jolt host read-line
  seam still allocates a complete stdin line before this boundary sees it."
  ([line]
   (decode-line line {:max-line-chars default-max-line-chars
                      :max-json-depth default-max-json-depth}))
  ([line {:keys [max-line-chars max-json-depth body-key-map]
          :or {max-line-chars default-max-line-chars
               max-json-depth default-max-json-depth
               body-key-map {}}}]
   (when-not (string? line)
     (fail! ::decode-failed
            "JSON-lines input must be a string"
            nil
            {:reason :not-a-string
             :value-class (str (class line))}))
   (when-not (and (integer? max-line-chars) (pos? max-line-chars)
                  (integer? max-json-depth) (pos? max-json-depth))
     (fail! ::invalid-config
            "JSON-lines decode limits must be positive integers"
            nil
            {:reason :invalid-decode-limits}))
   (when-not (valid-body-key-map? body-key-map)
     (fail! ::invalid-config
            "JSON-lines workload key map must map strings to existing keywords"
            nil
            {:reason :invalid-body-key-map}))
   (validate-json-shape! line max-line-chars max-json-depth)
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
     (normalize-known-keys
      outer-wire-keys
      (if (map? (get parsed "body"))
        (assoc parsed "body"
               (normalize-known-keys
                ;; Workload extensions cannot replace node-reserved mappings.
                (merge body-key-map body-wire-keys)
                (get parsed "body")))
        parsed)))))

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
  ([read-line! handle!]
   (pump! read-line! handle! {}))
  ([read-line! handle! decode-options]
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
       (handle! (decode-line line decode-options))
       (recur)))
   nil))

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
