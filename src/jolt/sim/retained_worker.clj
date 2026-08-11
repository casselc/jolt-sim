(ns jolt.sim.retained-worker
  "Cooperative child for one retained Jolt application.

  The parent owns the artifact directory and publishes immutable, sequential
  command files.  This worker loads one explicitly marked adapter, gives it one
  `serve!` closure, and publishes exactly one receipt per admitted command.
  Application failures are receipts and do not stop the loop.  Protocol or
  adapter failures are fatal and receive a best-effort terminal document.

  This is a cooperative control boundary, not a security sandbox."
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]
            [jolt.sim.trace :as trace]))

(def protocol-version 1)

(def ^:private protocol-key :jolt.sim.retained/protocol)
(def ^:private instance-key :jolt.sim.retained/instance-id)
(def ^:private adapter-key :jolt.sim.retained/adapter)
(def ^:private input-key :jolt.sim.retained/input)
(def ^:private sequence-key :jolt.sim.retained/sequence)
(def ^:private command-key :jolt.sim.retained/command)
(def ^:private status-key :jolt.sim.retained/status)
(def ^:private value-key :jolt.sim.retained/value)
(def ^:private error-key :jolt.sim.retained/error)

(def ^:private startup-keys
  #{protocol-key instance-key adapter-key input-key})
(def ^:private command-keys
  #{protocol-key instance-key sequence-key command-key})
(def ^:private document-byte-limit 1048576)
(def ^:private error-text-limit 4096)
(def ^:private poll-ms 10)
(def ^:private end-of-input (Object.))

(defn- protocol-error [reason data]
  (ex-info "jolt-sim retained worker protocol violation"
           (merge {:type ::protocol-error :reason reason} data)))

(defn- namespaced-symbol? [value]
  (and (symbol? value) (some? (namespace value))))

(defn- bounded-text [value]
  (let [text (str (or value ""))]
    (if (<= (count text) error-text-limit)
      text
      (str (subs text 0 error-text-limit) "..."))))

(defn- safe-error [phase error]
  (let [data (ex-data error)]
    (cond-> {:phase phase
             :kind :jolt.sim/exception
             :class (bounded-text (class error))
             :message (bounded-text (or (ex-message error) (str error)))}
      (keyword? (:type data)) (assoc :type (:type data))
      (keyword? (:reason data)) (assoc :reason (:reason data)))))

(defn- read-prefix [path limit]
  (let [stream (java.io.FileInputStream. path)]
    (try
      (let [buffer (byte-array limit)
            filled (loop [offset 0]
                     (if (>= offset limit)
                       offset
                       (let [n (.read stream buffer offset (- limit offset))]
                         (if (neg? n)
                           offset
                           (recur (+ offset n))))))
            extra? (and (= filled limit) (not (neg? (.read stream))))
            observed (try
                       (.length (java.io.File. path))
                       (catch :default _ filled))]
        {:bytes (max filled observed)
         :truncated? (or extra? (> observed filled))
         :text (String. (java.util.Arrays/copyOf buffer filled) "UTF-8")})
      (finally
        (.close stream)))))

(defn- read-one-edn [text]
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-input)
          [trailing _] (read+string reader false end-of-input)]
      (when (identical? value end-of-input)
        (throw (protocol-error :empty-document {})))
      (when-not (identical? trailing end-of-input)
        (throw (protocol-error :trailing-document {})))
      (edn/read-string text))
    (catch :default error
      (if (= ::protocol-error (:type (ex-data error)))
        (throw error)
        (throw (protocol-error :malformed-edn
                               {:error (safe-error :document-reading error)}))))))

(defn- read-document [path]
  (let [{:keys [bytes truncated? text]} (read-prefix path document-byte-limit)]
    (when (or truncated? (> bytes document-byte-limit))
      (throw (protocol-error :document-too-large
                             {:max-bytes document-byte-limit
                              :observed-bytes bytes})))
    (read-one-edn text)))

(defn- exact-keys! [kind expected document]
  (when-not (map? document)
    (throw (protocol-error :not-a-map
                           {:document kind
                            :value-class (str (class document))})))
  (when-not (= expected (set (keys document)))
    (throw (protocol-error :wrong-keys
                           {:document kind
                            :expected expected
                            :actual (set (keys document))})))
  document)

(defn- validate-coordinate! [kind instance-id sequence document]
  (when-not (= protocol-version (get document protocol-key))
    (throw (protocol-error :protocol-version
                           {:document kind
                            :expected protocol-version
                            :actual (get document protocol-key)})))
  (when-not (= instance-id (get document instance-key))
    (throw (protocol-error :instance-id {:document kind})))
  (when (some? sequence)
    (when-not (= sequence (get document sequence-key))
      (throw (protocol-error :sequence
                             {:document kind
                              :expected sequence
                              :actual (get document sequence-key)}))))
  document)

(defn- checked-startup [document]
  (exact-keys! :startup startup-keys document)
  (when-not (= protocol-version (get document protocol-key))
    (throw (protocol-error :protocol-version
                           {:document :startup
                            :expected protocol-version
                            :actual (get document protocol-key)})))
  (let [instance-id (get document instance-key)
        adapter (get document adapter-key)
        input (get document input-key)]
    (when-not (and (string? instance-id) (seq instance-id))
      (throw (protocol-error :invalid-instance-id {})))
    (when-not (namespaced-symbol? adapter)
      (throw (protocol-error :invalid-adapter {:adapter adapter})))
    (when-not (trace/canonical-form? input)
      (throw (protocol-error :invalid-input {})))
    {:instance-id instance-id
     :adapter adapter
     :input (trace/restore-value input)}))

(defn- resolve-adapter! [adapter]
  (require (symbol (namespace adapter)))
  (let [adapter-var (resolve adapter)]
    (when-not adapter-var
      (throw (protocol-error :adapter-not-found {:adapter adapter})))
    (when-not (true? (:jolt.sim/retained-adapter (meta adapter-var)))
      (throw (protocol-error :adapter-not-marked {:adapter adapter})))
    (when-not (fn? @adapter-var)
      (throw (protocol-error :adapter-not-callable {:adapter adapter})))
    adapter-var))

(defn- checked-command [instance-id sequence document]
  (exact-keys! :command command-keys document)
  (validate-coordinate! :command instance-id sequence document)
  (let [encoded (get document command-key)]
    (when-not (trace/canonical-form? encoded)
      (throw (protocol-error :invalid-command-payload
                             {:sequence sequence})))
    (trace/restore-value encoded)))

(defn- path-in [dir filename]
  (str (fs/path dir filename)))

(defn- command-basename [sequence]
  (format "command-%020d.edn" sequence))

(defn- receipt-basename [sequence]
  (format "receipt-%020d.edn" sequence))

(defn- publish-document! [path document]
  (let [partial (str path ".partial")]
    (when (or (fs/exists? partial) (fs/exists? path))
      (throw (protocol-error :publication-path-exists {:path path})))
    (spit partial (trace/canonical-edn document))
    (when-not (= document (read-document partial))
      (throw (protocol-error :publication-verification {:path path})))
    (fs/move partial path {:atomic-move true})
    document))

(defn- ready-document [instance-id]
  {protocol-key protocol-version
   instance-key instance-id
   status-key :ready})

(defn- receipt-document [instance-id sequence outcome]
  (if (:ok? outcome)
    {protocol-key protocol-version
     instance-key instance-id
     sequence-key sequence
     status-key :completed
     value-key (trace/canonical-value (:value outcome)
                                      [:retained :receipt sequence])}
    {protocol-key protocol-version
     instance-key instance-id
     sequence-key sequence
     status-key :failed
     error-key (trace/canonical-value
                (safe-error :application-command (:error outcome))
                [:retained :receipt sequence :error])}))

(defn- terminal-document [instance-id status detail]
  {protocol-key protocol-version
   instance-key instance-id
   status-key status
   value-key (trace/canonical-value detail [:retained :terminal])})

(defn- wait-for-command! [command-dir sequence]
  (let [path (path-in command-dir (command-basename sequence))]
    (loop []
      (if (fs/exists? path)
        path
        (do
          (Thread/sleep poll-ms)
          (recur))))))

(defn- serve-loop! [instance-id ready-path command-dir receipt-dir dispatch]
  (when-not (fn? dispatch)
    (throw (protocol-error :dispatch-not-callable {})))
  ;; Reaching this closure proves the adapter has established its application
  ;; and any controller scope surrounding `serve!`.
  (publish-document! ready-path (ready-document instance-id))
  (loop [sequence 0]
    (let [command-path (wait-for-command! command-dir sequence)
          command (checked-command instance-id sequence
                                   (read-document command-path))
          outcome (try
                    {:ok? true :value (dispatch command)}
                    (catch :default error
                      {:ok? false :error error}))
          dispatch-value (:value outcome)
          terminal? (and (:ok? outcome)
                         (map? dispatch-value)
                         (= #{:terminal? :value}
                            (set (keys dispatch-value)))
                         (true? (:terminal? dispatch-value)))
          accepted-value (when (:ok? outcome)
                           (do
                             (when-not (and (map? dispatch-value)
                                            (= #{:terminal? :value}
                                               (set (keys dispatch-value)))
                                            (contains? #{true false}
                                                       (:terminal? dispatch-value)))
                               (throw (protocol-error
                                       :invalid-dispatch-result
                                       {:sequence sequence})))
                             (:value dispatch-value)))
          receipt-outcome (if (:ok? outcome)
                            {:ok? true :value accepted-value}
                            outcome)
          receipt-path (path-in receipt-dir (receipt-basename sequence))]
      (publish-document! receipt-path
                         (receipt-document instance-id sequence receipt-outcome))
      (if terminal?
        {:sequence sequence :value accepted-value}
        (recur (inc sequence))))))

(defn run-worker!
  "Runs one retained worker from its five parent-owned protocol paths."
  [startup-path ready-path command-dir receipt-dir terminal-path]
  (let [{:keys [instance-id adapter input]}
        (checked-startup (read-document startup-path))
        adapter-var (resolve-adapter! adapter)
        serve-count (atom 0)]
    (try
      (let [result
            (@adapter-var
             {:input input
              :serve!
              (fn [dispatch]
                (when-not (= 1 (swap! serve-count inc))
                  (throw (protocol-error :serve-called-more-than-once {})))
                (serve-loop! instance-id ready-path command-dir
                             receipt-dir dispatch))})]
        (when-not (= 1 @serve-count)
          (throw (protocol-error :serve-not-called {})))
        (publish-document!
         terminal-path
         (terminal-document instance-id :completed
                            {:reason :adapter-returned}))
        result)
      (catch :default error
        (try
          (when-not (fs/exists? terminal-path)
            (publish-document!
             terminal-path
             (terminal-document instance-id :failed
                                (safe-error :retained-worker error))))
          (catch :default _ nil))
        (throw error)))))

(defn -main [& args]
  (when-not (= 5 (count args))
    (throw (protocol-error
            :worker-arguments
            {:expected ["startup-path" "ready-path" "command-dir"
                        "receipt-dir" "terminal-path"]
             :actual-count (count args)})))
  (apply run-worker! args))
