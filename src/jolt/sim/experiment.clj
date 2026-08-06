(ns jolt.sim.experiment
  "Closed experiment manifests and immutable connection/check plans.

  Version 1 assembles trusted connection and check packs around an existing
  scenario. It deliberately contains no scenario symbol, scheduler, search
  state, seed, or mutable execution state: the existing trusted defsim and
  fresh-worker path still executes ordinary application code. Node/workload
  packs can extend a later manifest version without allowing uploaded EDN to
  resolve executable names."
  (:require [clojure.set :as set]
            [jolt.sim.handler-pack :as handler-pack]
            [jolt.sim.monitor :as monitor]
            [jolt.sim.pack-registry :as packs]
            [jolt.sim.presentation :as presentation]))

(def manifest-version 1)
(def plan-version 1)

(deftype ^:private ExperimentPlan [data])

(def ^:private type-key :jolt.sim.experiment/type)
(def ^:private manifest-keys
  #{:jolt.sim.experiment/version :id :nodes :connections :profiles :checks})
(def ^:private node-keys #{:ports})
(def ^:private port-keys #{:direction :capabilities})
(def ^:private connection-keys #{:from :to :pack :config})
(def ^:private profile-keys #{:connections})
(def ^:private profile-connection-keys #{:mode :params})
(def ^:private check-keys #{:pack :config})
(def ^:private binding-field-keys
  #{:consumes :handler-packs :clock :mechanism-probe :history-projector
    :monitor-specs :presentation-registry :native-fallback})
(def ^:private check-field-keys #{:monitor-specs :presentation-registry})
(def ^:private compiled-connection-keys
  #{:from :to :pack-id :capabilities :mode :binding})
(def ^:private plan-keys
  #{:jolt.sim.experiment/type :jolt.sim.experiment-plan/version
    :experiment-id :profile-id :manifest :connections :checks
    :runtime-config :mechanism-probes :history-projectors :monitor-specs
    :presentation-registry})
(def ^:private derived-plan-keys
  #{:runtime-config :mechanism-probes :history-projectors :monitor-specs
    :presentation-registry})
(def ^:private allowed-directions #{:in :out})
(def ^:private native-modes #{:record :pass-through})
(def ^:private v1-modes #{:simulate :record :pass-through})

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- exact-map! [value expected-keys path]
  (when-not (and (map? value) (nil? (meta value)))
    (fail! ::invalid-manifest "experiment value must be a metadata-free map"
           {:path path}))
  (let [actual-keys (set (keys value))]
    (when-not (= expected-keys actual-keys)
      (fail! ::invalid-manifest "experiment map has the wrong keys"
             {:path path
              :missing (set/difference expected-keys actual-keys)
              :unknown-key-count
              (count (set/difference actual-keys expected-keys))})))
  value)

(defn- named-id? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- pack-id? [value]
  (and (named-id? value)
       (some? (re-matches #".+-v[1-9][0-9]*" (name value)))))

(defn- validate-port! [node-id port-id port]
  (exact-map! port port-keys [:nodes node-id :ports port-id])
  (when-not (contains? allowed-directions (:direction port))
    (fail! ::invalid-manifest "port direction must be :in or :out"
           {:path [:nodes node-id :ports port-id :direction]}))
  (when-not (and (set? (:capabilities port))
                 (seq (:capabilities port))
                 (every? named-id? (:capabilities port)))
    (fail! ::invalid-manifest
           "port capabilities must be a nonempty set of namespaced keywords"
           {:path [:nodes node-id :ports port-id :capabilities]}))
  port)

(defn- validate-nodes! [nodes]
  (when-not (and (map? nodes) (nil? (meta nodes)) (seq nodes))
    (fail! ::invalid-manifest "experiment declares at least one node"
           {:path [:nodes]}))
  (doseq [[node-id node] nodes]
    (when-not (keyword? node-id)
      (fail! ::invalid-manifest "node ids are keywords"
             {:path [:nodes]}))
    (exact-map! node node-keys [:nodes node-id])
    (when-not (and (map? (:ports node)) (nil? (meta (:ports node)))
                   (seq (:ports node)))
      (fail! ::invalid-manifest "nodes declare at least one port"
             {:path [:nodes node-id :ports]}))
    (doseq [[port-id port] (:ports node)]
      (when-not (keyword? port-id)
        (fail! ::invalid-manifest "port ids are keywords"
               {:path [:nodes node-id :ports]}))
      (validate-port! node-id port-id port)))
  nodes)

(defn- endpoint-port [nodes endpoint path expected-direction]
  (when-not (and (vector? endpoint) (nil? (meta endpoint))
                 (= 2 (count endpoint))
                 (every? keyword? endpoint))
    (fail! ::invalid-manifest "endpoint is exactly [node-id port-id]"
           {:path path}))
  (let [[node-id port-id] endpoint
        port (get-in nodes [node-id :ports port-id])]
    (when-not port
      (fail! ::invalid-manifest "endpoint names an unknown node or port"
             {:path path :endpoint endpoint}))
    (when-not (= expected-direction (:direction port))
      (fail! ::invalid-manifest "connection endpoint has the wrong direction"
             {:path path :endpoint endpoint
              :expected expected-direction :actual (:direction port)}))
    port))

(defn- validate-connections! [nodes connections]
  (when-not (and (map? connections) (nil? (meta connections))
                 (seq connections))
    (fail! ::invalid-manifest "experiment declares at least one connection"
           {:path [:connections]}))
  (doseq [[connection-id connection] connections]
    (when-not (keyword? connection-id)
      (fail! ::invalid-manifest "connection ids are keywords"
             {:path [:connections]}))
    (exact-map! connection connection-keys [:connections connection-id])
    (endpoint-port nodes (:from connection)
                   [:connections connection-id :from] :out)
    (endpoint-port nodes (:to connection)
                   [:connections connection-id :to] :in)
    (when-not (pack-id? (:pack connection))
      (fail! ::invalid-manifest "connection pack id is versioned and namespaced"
             {:path [:connections connection-id :pack]})))
  connections)

(defn- validate-profiles! [connection-ids profiles]
  (when-not (and (map? profiles) (nil? (meta profiles)) (seq profiles))
    (fail! ::invalid-manifest "experiment declares at least one profile"
           {:path [:profiles]}))
  (doseq [[profile-id profile] profiles]
    (when-not (keyword? profile-id)
      (fail! ::invalid-manifest "profile ids are keywords" {:path [:profiles]}))
    (exact-map! profile profile-keys [:profiles profile-id])
    (let [selections (:connections profile)]
      (when-not (and (map? selections) (nil? (meta selections))
                     (= connection-ids (set (keys selections))))
        (let [actual-ids (if (map? selections) (set (keys selections)) #{})]
          (fail! ::invalid-manifest
                 "every profile selects exactly every declared connection"
                 {:path [:profiles profile-id :connections]
                  :missing (set/difference connection-ids actual-ids)
                  :unknown-key-count
                  (count (set/difference actual-ids connection-ids))})))
      (doseq [[connection-id selection] selections]
        (exact-map! selection profile-connection-keys
                    [:profiles profile-id :connections connection-id])
        (when-not (keyword? (:mode selection))
          (fail! ::invalid-manifest "connection mode is a keyword"
                 {:path [:profiles profile-id :connections connection-id
                         :mode]}))
        (when-not (contains? v1-modes (:mode selection))
          (fail! ::unsupported-mode
                 "v1 supports :simulate, :record, and :pass-through only"
                 {:path [:profiles profile-id :connections connection-id :mode]
                  :mode (:mode selection)})))))
  profiles)

(defn- validate-checks! [checks]
  (when-not (and (vector? checks) (nil? (meta checks)))
    (fail! ::invalid-manifest "checks are a metadata-free vector"
           {:path [:checks]}))
  (doseq [[index check] (map-indexed vector checks)]
    (exact-map! check check-keys [:checks index])
    (when-not (pack-id? (:pack check))
      (fail! ::invalid-manifest "check pack id is versioned and namespaced"
             {:path [:checks index :pack]})))
  checks)

(defn validate-manifest!
  "Validates and returns one closed data manifest for connection/check v1."
  [manifest]
  (packs/ensure-data-only! manifest)
  (exact-map! manifest manifest-keys [])
  (when-not (= manifest-version (:jolt.sim.experiment/version manifest))
    (fail! ::unsupported-version "unsupported experiment manifest version"
           {:version (:jolt.sim.experiment/version manifest)}))
  (when-not (pack-id? (:id manifest))
    (fail! ::invalid-manifest "experiment id is versioned and namespaced"
           {:path [:id]}))
  (let [nodes (validate-nodes! (:nodes manifest))
        connections (validate-connections! nodes (:connections manifest))]
    (validate-profiles! (set (keys connections)) (:profiles manifest))
    (validate-checks! (:checks manifest)))
  manifest)

(defn- exact-binding-fields! [connection-id fields]
  (when-not (and (map? fields) (= binding-field-keys (set (keys fields))))
    (fail! ::invalid-binding "connection pack returned the wrong field shape"
           {:connection-id connection-id
            :actual (when (map? fields) (set (keys fields)))}))
  fields)

(defn- endpoint-capabilities [manifest endpoint]
  (get-in manifest [:nodes (nth endpoint 0) :ports (nth endpoint 1)
                    :capabilities]))

(defn- validate-consumes! [manifest connection-id connection expected fields]
  (let [consumes (:consumes fields)]
    (when-not (and (map? consumes)
                   (= #{:from :to} (set (keys consumes)))
                   (set? (:from consumes)) (seq (:from consumes))
                   (set? (:to consumes)) (seq (:to consumes))
                   (every? named-id? (:from consumes))
                   (every? named-id? (:to consumes)))
      (fail! ::invalid-binding "connection :consumes must name endpoint capabilities"
             {:connection-id connection-id}))
    (when-not (= expected consumes)
      (fail! ::invalid-binding
             "compiled connection capabilities differ from its descriptor"
             {:connection-id connection-id}))
    (doseq [[side endpoint] [[:from (:from connection)] [:to (:to connection)]]]
      (let [available (endpoint-capabilities manifest endpoint)
            required (get consumes side)]
        (when-not (set/subset? required available)
          (fail! ::capability-mismatch
                 "connection binding requires unavailable endpoint capabilities"
                 {:connection-id connection-id :side side
                  :required required :available available}))))))

(defn- validate-monitor-specs! [connection-id specs]
  (when-not (vector? specs)
    (fail! ::invalid-binding "monitor specs must be a vector"
           {:connection-id connection-id}))
  (doseq [spec specs] (monitor/validate-spec! spec))
  specs)

(defn- validate-connection-binding! [manifest connection-id connection
                                     expected-capabilities mode binding]
  (packs/validate-connection-binding! binding (:pack connection) mode)
  (let [fields (exact-binding-fields! connection-id (:fields binding))]
    (validate-consumes! manifest connection-id connection
                        expected-capabilities fields)
    (when-not (and (vector? (:handler-packs fields))
                   (or (nil? (:clock fields)) (fn? (:clock fields)))
                   (fn? (:mechanism-probe fields))
                   (fn? (:history-projector fields))
                   (set? (:native-fallback fields))
                   (empty? (:native-fallback fields)))
      (fail! ::invalid-binding "connection binding has invalid executable fields"
             {:connection-id connection-id}))
    (validate-monitor-specs! connection-id (:monitor-specs fields))
    (presentation/validate-registry! (:presentation-registry fields))
    ;; compose performs exact handler-pack validation and collision checks.
    (apply handler-pack/compose (:handler-packs fields))
    (cond
      (= :simulate mode)
      (when-not (or (seq (:handler-packs fields)) (fn? (:clock fields)))
        (fail! ::missing-interception
               "simulated connection has no executable interception binding"
               {:connection-id connection-id}))

      (contains? native-modes mode)
      (when (or (seq (:handler-packs fields)) (fn? (:clock fields)))
        (fail! ::invalid-binding
               "native observation modes cannot install simulated handlers"
               {:connection-id connection-id :mode mode}))

      :else (fail! ::unsupported-mode "unsupported v1 mode"
                   {:connection-id connection-id :mode mode}))
    binding))

(defn- preflight-capabilities! [registry manifest connection-id connection]
  (let [descriptor (packs/describe registry [:connection (:pack connection)])
        required (:capabilities descriptor)]
    (doseq [[side endpoint] [[:from (:from connection)] [:to (:to connection)]]]
      (let [available (endpoint-capabilities manifest endpoint)
            needed (get required side)]
        (when-not (set/subset? needed available)
          (fail! ::capability-mismatch
                 "connection pack is incompatible with endpoint capabilities"
                 {:connection-id connection-id :side side
                  :required needed :available available}))))
    required))

(defn- runtime-mode [compiled-connections]
  (let [modes (set (map :mode (vals compiled-connections)))]
    (cond
      (= #{:simulate} modes) :hermetic
      (every? native-modes modes) :observe
      :else (fail! ::unsupported-profile
                   "v1 cannot enforce mixed simulated and native connections"
                   {:modes modes}))))

(defn- add-presentations [result registry owner]
  (presentation/validate-registry! registry)
  (when-let [duplicates (seq (set/intersection (set (keys result))
                                              (set (keys registry))))]
    (fail! ::duplicate-presentation
           "more than one selected pack owns a presentation key"
           {:owner owner :keys (set duplicates)}))
  (merge result registry))

(defn- compile-checks [registry checks]
  (mapv
   (fn [[index check]]
     (let [binding (packs/compile-check registry [:check (:pack check)]
                                        {:config (:config check)})
           fields (:fields binding)]
       (when-not (and (map? fields)
                      (= check-field-keys (set (keys fields))))
         (fail! ::invalid-binding "check pack returned the wrong field shape"
                {:check-index index :pack-id (:pack check)}))
       (validate-monitor-specs! [:check index] (:monitor-specs fields))
       (presentation/validate-registry! (:presentation-registry fields))
       binding))
   (map-indexed vector checks)))

(defn- validate-check-binding! [index check binding]
  (packs/validate-check-binding! binding (:pack check))
  (let [fields (:fields binding)]
    (when-not (and (map? fields) (= check-field-keys (set (keys fields))))
      (fail! ::invalid-binding "check pack returned the wrong field shape"
             {:check-index index :pack-id (:pack check)}))
    (validate-monitor-specs! [:check index] (:monitor-specs fields))
    (presentation/validate-registry! (:presentation-registry fields)))
  binding)

(defn- derive-plan-fields [compiled-connections connection-ids check-bindings]
  (let [connection-fields (map #(get-in compiled-connections
                                        [% :binding :fields])
                               connection-ids)
        check-fields (map :fields check-bindings)
        handler-packs (mapcat :handler-packs connection-fields)
        clocks (vec (keep :clock connection-fields))
        _ (when (< 1 (count clocks))
            (fail! ::multiple-clocks
                   "more than one connection owns the runtime clock" {}))
        ffi-mode (runtime-mode compiled-connections)
        ffi-handlers (apply handler-pack/compose handler-packs)
        _ (when (and (= :observe ffi-mode) (seq ffi-handlers))
            (fail! ::invalid-plan "observe plans cannot install handlers" {}))
        runtime-config (cond-> {:ffi-mode ffi-mode}
                         (= :hermetic ffi-mode)
                         (assoc :ffi-handlers ffi-handlers)
                         (seq clocks) (assoc :clock (first clocks)))
        all-fields (concat connection-fields check-fields)
        monitor-specs (vec (mapcat :monitor-specs all-fields))
        monitor-ids (map :id monitor-specs)
        _ (when-not (= (count monitor-ids) (count (set monitor-ids)))
            (fail! ::duplicate-monitor "selected packs share a monitor id" {}))
        presentations
        (reduce (fn [result [index fields]]
                  (add-presentations result (:presentation-registry fields)
                                     index))
                {}
                (map-indexed vector all-fields))]
    {:runtime-config runtime-config
     :mechanism-probes
     (into {} (map (fn [[id compiled]]
                     [id (get-in compiled [:binding :fields
                                          :mechanism-probe])])
                   compiled-connections))
     :history-projectors
     (into {} (map (fn [[id compiled]]
                     [id (get-in compiled [:binding :fields
                                          :history-projector])])
                   compiled-connections))
     :monitor-specs monitor-specs
     :presentation-registry presentations}))

(defn- validate-plan-data! [plan]
  (when-not (and (map? plan)
                 (= plan-keys (set (keys plan)))
                 (= ::plan (get plan type-key))
                 (= plan-version (:jolt.sim.experiment-plan/version plan))
                 (map? (:connections plan))
                 (vector? (:checks plan))
                 (map? (:runtime-config plan))
                 (map? (:mechanism-probes plan))
                 (map? (:history-projectors plan))
                 (vector? (:monitor-specs plan))
                 (map? (:presentation-registry plan)))
    (fail! ::invalid-plan "value is not an exact experiment plan" {}))
  (let [manifest (validate-manifest! (:manifest plan))
        profile-id (:profile-id plan)
        expected-connections (:connections manifest)
        selections (get-in manifest [:profiles profile-id :connections])]
    (when-not (and (= (:id manifest) (:experiment-id plan)) selections)
      (fail! ::invalid-plan "plan identity does not match its manifest" {}))
    (when-not (= (set (keys expected-connections))
                 (set (keys (:connections plan))))
      (fail! ::invalid-plan "plan connections do not match its manifest" {}))
    (let [connection-ids (sort-by pr-str (keys expected-connections))]
      (doseq [connection-id connection-ids]
        (let [compiled (get-in plan [:connections connection-id])
              connection (get expected-connections connection-id)
              mode (get-in selections [connection-id :mode])]
          (when-not (and (map? compiled)
                         (= compiled-connection-keys (set (keys compiled)))
                         (= (:from connection) (:from compiled))
                         (= (:to connection) (:to compiled))
                         (= (:pack connection) (:pack-id compiled))
                         (= mode (:mode compiled)))
            (fail! ::invalid-plan "compiled connection identity was altered"
                   {:connection-id connection-id}))
          (validate-connection-binding! manifest connection-id connection
                                        (:capabilities compiled) mode
                                        (:binding compiled))))
      (when-not (= (count (:checks manifest)) (count (:checks plan)))
        (fail! ::invalid-plan "plan checks do not match its manifest" {}))
      (doseq [[index check binding]
              (map vector (range) (:checks manifest) (:checks plan))]
        (validate-check-binding! index check binding))
      (let [expected (derive-plan-fields (:connections plan) connection-ids
                                         (:checks plan))]
        (when-not (= expected (select-keys plan derived-plan-keys))
          (fail! ::invalid-plan "derived plan fields were altered" {})))))
  plan)

(defn- raw-plan-data [plan]
  (when-not (instance? ExperimentPlan plan)
    (fail! ::invalid-plan
           "value is not an opaque plan returned by compile-plan" {}))
  (.-data plan))

(defn validate-plan!
  "Returns plan only when it is the opaque direct output of compile-plan and
  its internal derived fields remain coherent. Plain maps, including copied
  or coordinated-tampered plan data, are never accepted as executable plans."
  [plan]
  (validate-plan-data! (raw-plan-data plan))
  plan)

(defn plan-data
  "Returns the validated process-local read model for Ripple/REPL inspection.

  The returned map is not itself an executable plan; modifying it cannot
  produce a value accepted by validate-plan! or future execution APIs."
  [plan]
  (validate-plan! plan)
  (raw-plan-data plan))

(defn compile-plan
  "Compiles one validated manifest/profile through an explicit trusted registry.

  The result only assembles runtime configuration and evidence/check hooks. It
  never runs the scenario or chooses a schedule."
  [registry manifest profile-id]
  (validate-manifest! manifest)
  (when-not (contains? (:profiles manifest) profile-id)
    (fail! ::unknown-profile "profile is not declared by the experiment"
           {:profile-id profile-id}))
  (let [selections (get-in manifest [:profiles profile-id :connections])
        connection-ids (sort-by pr-str (keys (:connections manifest)))
        compiled-connections
        (reduce
         (fn [result connection-id]
           (let [connection (get-in manifest [:connections connection-id])
                 selection (get selections connection-id)
                 mode (:mode selection)
                 required-capabilities
                 (preflight-capabilities! registry manifest connection-id
                                          connection)
                 binding
                 (packs/compile-connection
                  registry [:connection (:pack connection)]
                  {:mode mode :config (:config connection)
                   :params (:params selection)})]
             (validate-connection-binding! manifest connection-id connection
                                           required-capabilities mode binding)
             (assoc result connection-id
                    {:from (:from connection) :to (:to connection)
                     :pack-id (:pack connection)
                     :capabilities required-capabilities :mode mode
                     :binding binding})))
         {}
         connection-ids)
        check-bindings (compile-checks registry (:checks manifest))
        derived (derive-plan-fields compiled-connections connection-ids
                                    check-bindings)]
    (validate-plan!
     (ExperimentPlan.
      (merge
       {type-key ::plan
        :jolt.sim.experiment-plan/version plan-version
        :experiment-id (:id manifest)
        :profile-id profile-id
        :manifest manifest
        :connections compiled-connections
        :checks check-bindings}
       derived)))))
