(ns jolt.sim.viewer.experiment
  "Safe, read-only Ripple projections of compiled experiment plans.

  `jolt.sim.experiment/plan-data` is a process-local inspection value: it
  contains trusted executable bindings and must never be serialized. This
  namespace extracts a closed inert document first. Only that document may be
  written, uploaded, presented, or rendered."
  (:require [clojure.string :as string]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.trace :as trace]))

(def invalid-document ::invalid-document)
(def document-version 1)

(def ^:private document-keys
  #{:jolt.sim.viewer.experiment/version :kind :experiment-id :profile-id
    :nodes :connections :checks :runtime :counts :controls})
(def ^:private node-keys #{:id :ports})
(def ^:private port-keys #{:id :direction :capabilities})
(def ^:private connection-keys
  #{:id :from :to :pack-id :mode :capabilities :handler-owners})
(def ^:private capability-keys #{:from :to})
(def ^:private handler-owner-keys #{:pack-id :handler-count})
(def ^:private check-keys #{:index :pack-id})
(def ^:private runtime-keys #{:ffi-mode :handler-count})
(def ^:private count-keys
  #{:nodes :ports :connections :checks :handler-packs :handlers
    :monitors :presentations})
(def ^:private control-keys #{:replay :step :perturb})
(def ^:private control-value-keys #{:enabled? :reason})
(def ^:private allowed-directions #{:in :out})
(def ^:private allowed-modes #{:simulate :record :pass-through})
(def ^:private allowed-ffi-modes #{:hermetic :observe})
(def ^:private disabled-control {:enabled? false :reason :inspection-only})
(def ^:private disabled-controls
  {:replay disabled-control :step disabled-control :perturb disabled-control})
(def ^:private end-of-input (atom nil))

(defn- fail! [reason]
  (throw (ex-info "Invalid Ripple experiment-plan document"
                  {:type invalid-document :reason reason})))

(defn- exact-map? [value expected-keys]
  (and (map? value)
       (nil? (meta value))
       (= expected-keys (set (keys value)))))

(defn- named-keyword? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- keyword-vector? [value namespaced?]
  (and (vector? value)
       (nil? (meta value))
       (every? (if namespaced? named-keyword? keyword?) value)
       (= value (vec (sort-by pr-str value)))
       (= (count value) (count (set value)))))

(defn- endpoint? [value]
  (and (vector? value) (nil? (meta value)) (= 2 (count value))
       (every? keyword? value)))

(defn- natural? [value]
  (and (integer? value) (not (neg? value))))

(defn- sorted-unique-by-id? [values]
  (and (= values (vec (sort-by (comp pr-str :id) values)))
       (= (count values) (count (set (map :id values))))))

(defn- valid-port? [port]
  (and (exact-map? port port-keys)
       (keyword? (:id port))
       (contains? allowed-directions (:direction port))
       (seq (:capabilities port))
       (keyword-vector? (:capabilities port) true)))

(defn- valid-node? [node]
  (and (exact-map? node node-keys)
       (keyword? (:id node))
       (vector? (:ports node))
       (nil? (meta (:ports node)))
       (seq (:ports node))
       (every? valid-port? (:ports node))
       (sorted-unique-by-id? (:ports node))))

(defn- valid-owner? [owner]
  (and (exact-map? owner handler-owner-keys)
       (named-keyword? (:pack-id owner))
       (natural? (:handler-count owner))))

(defn- valid-connection? [connection]
  (and (exact-map? connection connection-keys)
       (keyword? (:id connection))
       (endpoint? (:from connection))
       (endpoint? (:to connection))
       (named-keyword? (:pack-id connection))
       (contains? allowed-modes (:mode connection))
       (exact-map? (:capabilities connection) capability-keys)
       (seq (get-in connection [:capabilities :from]))
       (seq (get-in connection [:capabilities :to]))
       (keyword-vector? (get-in connection [:capabilities :from]) true)
       (keyword-vector? (get-in connection [:capabilities :to]) true)
       (vector? (:handler-owners connection))
       (nil? (meta (:handler-owners connection)))
       (every? valid-owner? (:handler-owners connection))
       (= (:handler-owners connection)
          (vec (sort-by (comp pr-str :pack-id) (:handler-owners connection))))
       (= (count (:handler-owners connection))
          (count (set (map :pack-id (:handler-owners connection)))))))

(defn- valid-check? [check]
  (and (exact-map? check check-keys)
       (natural? (:index check))
       (named-keyword? (:pack-id check))))

(defn- endpoint-port [nodes [node-id port-id]]
  (some (fn [node]
          (when (= node-id (:id node))
            (some #(when (= port-id (:id %)) %) (:ports node))))
        nodes))

(defn- coherent-connection? [nodes connection]
  (let [from (endpoint-port nodes (:from connection))
        to (endpoint-port nodes (:to connection))]
    (and (= :out (:direction from))
         (= :in (:direction to))
         (every? (set (:capabilities from))
                 (get-in connection [:capabilities :from]))
         (every? (set (:capabilities to))
                 (get-in connection [:capabilities :to])))))

(defn- derived-counts [document]
  (let [connections (:connections document)]
    {:nodes (count (:nodes document))
     :ports (reduce + 0 (map #(count (:ports %)) (:nodes document)))
     :connections (count connections)
     :checks (count (:checks document))
     :handler-packs (reduce + 0 (map #(count (:handler-owners %)) connections))
     :handlers (reduce + 0
                       (mapcat #(map :handler-count (:handler-owners %))
                               connections))
     :monitors (get-in document [:counts :monitors])
     :presentations (get-in document [:counts :presentations])}))

(defn validate-document!
  "Validates and returns one closed, inert experiment-plan inspector document."
  [document]
  (when-not (exact-map? document document-keys) (fail! :wrong-document-shape))
  (when-not (= document-version (:jolt.sim.viewer.experiment/version document))
    (fail! :unsupported-version))
  (when-not (= :jolt.sim.kind/experiment-plan (:kind document))
    (fail! :wrong-kind))
  (when-not (and (named-keyword? (:experiment-id document))
                 (keyword? (:profile-id document)))
    (fail! :invalid-identity))
  (when-not (and (vector? (:nodes document))
                 (seq (:nodes document))
                 (every? valid-node? (:nodes document))
                 (sorted-unique-by-id? (:nodes document)))
    (fail! :invalid-nodes))
  (when-not (and (vector? (:connections document))
                 (seq (:connections document))
                 (every? valid-connection? (:connections document))
                 (every? #(coherent-connection? (:nodes document) %)
                         (:connections document))
                 (sorted-unique-by-id? (:connections document)))
    (fail! :invalid-connections))
  (when-not (and (vector? (:checks document))
                 (every? valid-check? (:checks document))
                 (= (mapv :index (:checks document))
                    (vec (range (count (:checks document))))))
    (fail! :invalid-checks))
  (when-not (and (exact-map? (:runtime document) runtime-keys)
                 (contains? allowed-ffi-modes (get-in document [:runtime :ffi-mode]))
                 (natural? (get-in document [:runtime :handler-count])))
    (fail! :invalid-runtime))
  (when-not (and (exact-map? (:counts document) count-keys)
                 (every? natural? (vals (:counts document))))
    (fail! :invalid-counts))
  (when-not (and (exact-map? (:controls document) control-keys)
                 (every? #(and (exact-map? % control-value-keys)
                               (= disabled-control %))
                         (vals (:controls document))))
    (fail! :invalid-controls))
  (when-not (= (:counts document) (derived-counts document))
    (fail! :incoherent-counts))
  (when-not (= (get-in document [:runtime :handler-count])
               (get-in document [:counts :handlers]))
    (fail! :incoherent-runtime-count))
  ;; A final canonicalization pass rejects functions, mutable arrays, metadata,
  ;; and every other value outside the durable trace data model.
  (try
    (trace/canonical-value document [:experiment-plan])
    (catch :default _ (fail! :non-inert-value)))
  document)

(defn- sorted-keywords [values]
  (vec (sort-by pr-str values)))

(defn- owner-projection [pack]
  {:pack-id (:id pack) :handler-count (count (:handlers pack))})

(defn plan-data->document
  "Extracts the safe persistent projection of `experiment/plan-data`.

  Executable binding values, handler values, manifest configuration, profile
  parameters, worlds, probes, projectors, monitors, presenters, and ambient
  data are deliberately never traversed into the result."
  [plan]
  (let [manifest (:manifest plan)
        nodes (mapv
               (fn [[node-id node]]
                 {:id node-id
                  :ports
                  (mapv (fn [[port-id port]]
                          {:id port-id
                           :direction (:direction port)
                           :capabilities (sorted-keywords (:capabilities port))})
                        (sort-by (comp pr-str key) (:ports node)))})
               (sort-by (comp pr-str key) (:nodes manifest)))
        connections
        (mapv
         (fn [[connection-id connection]]
           (let [packs (get-in connection [:binding :fields :handler-packs])]
             {:id connection-id
              :from (:from connection)
              :to (:to connection)
              :pack-id (:pack-id connection)
              :mode (:mode connection)
              :capabilities
              {:from (sorted-keywords (get-in connection [:capabilities :from]))
               :to (sorted-keywords (get-in connection [:capabilities :to]))}
              :handler-owners
              (mapv owner-projection (sort-by (comp pr-str :id) packs))}))
         (sort-by (comp pr-str key) (:connections plan)))
        checks (mapv (fn [index check]
                       {:index index :pack-id (:pack check)})
                     (range)
                     (:checks manifest))
        handler-count (count (get-in plan [:runtime-config :ffi-handlers] {}))
        counts {:nodes (count nodes)
                :ports (reduce + 0 (map #(count (:ports %)) nodes))
                :connections (count connections)
                :checks (count checks)
                :handler-packs
                (reduce + 0 (map #(count (:handler-owners %)) connections))
                :handlers handler-count
                :monitors (count (:monitor-specs plan))
                :presentations (count (:presentation-registry plan))}]
    (validate-document!
     {:jolt.sim.viewer.experiment/version document-version
      :kind :jolt.sim.kind/experiment-plan
      :experiment-id (:experiment-id plan)
      :profile-id (:profile-id plan)
      :nodes nodes
      :connections connections
      :checks checks
      :runtime {:ffi-mode (get-in plan [:runtime-config :ffi-mode])
                :handler-count handler-count}
      :counts counts
      :controls disabled-controls})))

(defn read-edn
  "Reads exactly one inert experiment-plan document from EDN."
  [text]
  (when-not (string? text) (fail! :not-a-string))
  (try
    (let [reader (__string-reader text)
          [document _] (read+string reader false end-of-input)
          [trailing _] (read+string reader false end-of-input)]
      (when (identical? end-of-input document) (fail! :empty-input))
      (when-not (identical? end-of-input trailing) (fail! :trailing-edn))
      (validate-document! document))
    (catch :default error
      (if (= invalid-document (:type (ex-data error)))
        (throw error)
        (fail! :unreadable-edn)))))

(defn canonical-edn [document]
  (trace/canonical-edn (validate-document! document)))

(defn- field [label value] {:label label :value value})

(defn- item-presenter [summary fields]
  (fn [event]
    (let [item (nth event 2)]
      {:summary (summary item)
       :fields (mapv (fn [[label accessor]] (field label (accessor item))) fields)})))

(def ^:private inspector-registry
  {:jolt.sim.viewer.experiment/identity
   {:kind :jolt.sim.kind/experiment-identity
    :present (item-presenter
              #(str "Experiment " (:experiment-id %))
              [["Profile" :profile-id]])}
   :jolt.sim.viewer.experiment/runtime
   {:kind :jolt.sim.kind/experiment-runtime
    :present (item-presenter
              #(str "Runtime FFI mode " (name (:ffi-mode %)))
              [["Handler count" :handler-count]])}
   :jolt.sim.viewer.experiment/node
   {:kind :jolt.sim.kind/experiment-node
    :present (item-presenter #(str "Node " (:id %)) [["Ports" :ports]])}
   :jolt.sim.viewer.experiment/connection
   {:kind :jolt.sim.kind/experiment-connection
    :present (item-presenter
              #(str "Connection " (:id %))
              [["From" :from] ["To" :to] ["Pack" :pack-id]
               ["Mode" :mode] ["Capabilities" :capabilities]
               ["Handler ownership" :handler-owners]])}
   :jolt.sim.viewer.experiment/check
   {:kind :jolt.sim.kind/experiment-check
    :present (item-presenter #(str "Check " (:index %)) [["Pack" :pack-id]])}
   :jolt.sim.viewer.experiment/counts
   {:kind :jolt.sim.kind/experiment-counts
    :present (item-presenter (constantly "Compiled plan inventory")
                             [["Counts" identity]])}
   :jolt.sim.viewer.experiment/controls
   {:kind :jolt.sim.kind/experiment-controls
    :present (item-presenter (constantly "Execution controls are disabled")
                             [["Controls" identity]])}})

(defn- document-events [document]
  (vec
   (concat
    [[:jolt.sim.viewer.experiment/identity 0
      (select-keys document [:experiment-id :profile-id])]
     [:jolt.sim.viewer.experiment/runtime 0 (:runtime document)]]
    (map #(vector :jolt.sim.viewer.experiment/node 0 %) (:nodes document))
    (map #(vector :jolt.sim.viewer.experiment/connection 0 %)
         (:connections document))
    (map #(vector :jolt.sim.viewer.experiment/check 0 %) (:checks document))
    [[:jolt.sim.viewer.experiment/counts 0 (:counts document)]
     [:jolt.sim.viewer.experiment/controls 0 (:controls document)]])))

(defn document->view-model
  "Returns specialized kind rows for one validated inert plan document."
  ([document] (document->view-model document nil))
  ([document custom-registry]
   (validate-document! document)
   {:kind (:kind document)
    :experiment-id (:experiment-id document)
    :profile-id (:profile-id document)
    :rows (presentation/events->rows
           (presentation/registry inspector-registry custom-registry)
           (document-events document))
    :controls (:controls document)}))

(defn- html-escape [value]
  (-> (str value)
      (string/replace "&" "&amp;")
      (string/replace "<" "&lt;")
      (string/replace ">" "&gt;")
      (string/replace "\"" "&quot;")
      (string/replace "'" "&#39;")))

(defn- row-html [row]
  (str "<article class=\"plan-row\" data-kind=\""
       (html-escape (:kind-name row)) "\"><h2>"
       (html-escape (:summary row)) "</h2>"
       (apply str
              (map (fn [field]
                     (str "<div class=\"field\"><strong>"
                          (html-escape (:label field))
                          ":</strong> <code>"
                          (html-escape (:value-edn field))
                          "</code></div>"))
                   (:fields row)))
       "</article>"))

(defn document->html
  "Renders the safe projection only; no process-local plan value enters HTML."
  ([document] (document->html document nil))
  ([document custom-registry]
   (let [view (document->view-model document custom-registry)]
     (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
          "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
          "<title>Experiment plan</title><style>"
          "body{font:15px system-ui,sans-serif;margin:1.5rem;max-width:76rem}"
          ".notice,.plan-row{border:1px solid #8886;border-radius:.5rem;padding:1rem;margin:1rem 0}"
          ".notice{border-color:#b86;background:#b861}.field{margin:.45rem 0}"
          "code{white-space:pre-wrap;overflow-wrap:anywhere}button{margin-right:.5rem}"
          "</style></head><body><h1>Experiment plan</h1>"
          "<p><code>" (html-escape (:experiment-id view)) "</code> / <code>"
          (html-escape (:profile-id view)) "</code></p>"
          "<section class=\"notice\"><strong>Inspection only.</strong> This inert projection cannot execute, replay, step, or perturb an experiment.<div>"
          "<button disabled aria-disabled=\"true\">Replay</button>"
          "<button disabled aria-disabled=\"true\">Step</button>"
          "<button disabled aria-disabled=\"true\">Perturb</button></div></section>"
          (apply str (map row-html (:rows view)))
          "</body></html>"))))
