(ns jolt.sim.workbench-session
  "Live, UI-neutral ownership of one persistent jolt.sim.workbench document.

  A WorkbenchSession serializes REPL and UI mutations with one lock. The
  underlying document remains the portable source of truth and can be exported
  as canonical EDN at any revision. Trusted renderer functions stay in the
  session-owned immutable kind registry; they never enter the saved document.

  `datafy` returns a cheap summary. `nav` over its exact revision token expands
  current items into bounded presentation frames. Renderer failures are
  advisory and retain canonical source EDN. A failed observational `tap>` runs
  after commit and never changes the mutation result."
  (:require [clojure.core.protocols :as protocols]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.trace :as trace]
            [jolt.sim.workbench :as workbench]))

(def rejected :jolt.sim.workbench-session/rejected)

(def ^:private default-visible-items 128)
(def ^:private maximum-visible-items 1024)
(def ^:private config-keys #{:document :kind-registry :visible-items})

(def ^:private snapshot-operation (Object.))
(def ^:private frame-operation (Object.))
(def ^:private document-operation (Object.))
(def ^:private append-item-operation (Object.))
(def ^:private put-rule-operation (Object.))
(def ^:private remove-rule-operation (Object.))
(def ^:private set-kind-operation (Object.))
(def ^:private clear-kind-operation (Object.))

(defn- reject! [reason detail]
  (throw (ex-info "jolt-sim workbench session rejected an operation"
                  {:type rejected :reason reason :detail detail})))

(defn- exact-keys? [value keys]
  (and (map? value)
       (= (count value) (count keys))
       (every? #(contains? value %) keys)))

(defn- validate-config! [config]
  (when-not (map? config)
    (reject! :config-not-a-map (str (class config))))
  (let [unknown (remove config-keys (keys config))]
    (when (seq unknown)
      (reject! :unknown-config-keys (vec (sort unknown)))))
  (let [document (get config :document (workbench/empty-document))
        registry (get config :kind-registry {})
        visible (get config :visible-items default-visible-items)]
    (workbench/validate-document! document)
    (presentation/validate-kind-registry! registry)
    (when-not (and (integer? visible)
                   (<= 1 visible maximum-visible-items))
      (reject! :visible-items-out-of-range
               {:minimum 1 :maximum maximum-visible-items
                :value visible}))
    {:document document :kind-registry registry :visible-items visible}))

(defn- bounded-error [error]
  (let [data (ex-data error)]
    {:type (if (keyword? (:type data)) (:type data) :jolt.sim/error)
     :reason (if (keyword? (:reason data)) (:reason data) :unknown)}))

(defn- item-frame [document registry stored]
  (let [item-id (:id stored)
        source-revision (:source-revision stored)
        source-edn (trace/canonical-edn
                    (trace/restore-value (:value stored)))]
    (try
      (let [{:keys [selection presentation]}
            (workbench/present-item document registry item-id source-revision)]
        {:coordinate {:item-id item-id :source-revision source-revision}
         :source-kind (:source-kind stored)
         :schema-id (:schema-id stored)
         :source-fingerprint (:source-fingerprint stored)
         :provenance (:provenance stored)
         :selection selection
         :presentation presentation
         :presentation-error nil
         :source-edn source-edn})
      (catch :default error
        {:coordinate {:item-id item-id :source-revision source-revision}
         :source-kind (:source-kind stored)
         :schema-id (:schema-id stored)
         :source-fingerprint (:source-fingerprint stored)
         :provenance (:provenance stored)
         :selection (workbench/resolve-kind document item-id source-revision)
         :presentation nil
         :presentation-error (bounded-error error)
         :source-edn source-edn}))))

(defn- current-items-in-journal-order [document snapshot]
  (let [current-by-coordinate
        (into {}
              (map (fn [item]
                     [[(:id item) (:source-revision item)] item]))
              (:current-items snapshot))]
    (->> (:jolt.sim.workbench/journal document)
         (keep (fn [event]
                 (when (= :item/append (:op event))
                   (let [item (:item event)]
                     (get current-by-coordinate
                          [(:id item) (:source-revision item)])))))
         vec)))

(defn- frame-state [state]
  (let [{:keys [document kind-registry visible-items]} state
        snapshot (workbench/snapshot document)
        current (current-items-in-journal-order document snapshot)
        overflow (max 0 (- (count current) visible-items))
        visible (if (pos? overflow) (subvec current overflow) current)]
    {:version 1
     :kind :jolt.sim.kind/workbench-frame
     :revision (:revision snapshot)
     :available-kinds (presentation/available-kinds kind-registry)
     :item-count (:item-count snapshot)
     :current-item-count (count current)
     :omitted-item-count overflow
     :journal-count (count (:jolt.sim.workbench/journal document))
     :items (mapv #(item-frame document kind-registry %) visible)
     :rules (:rules snapshot)
     :overrides (:overrides snapshot)}))

(defn- snapshot-state [state]
  (let [{:keys [document kind-registry visible-items]} state
        current (workbench/snapshot document)]
    {:kind :jolt.sim.kind/workbench-session
     :revision (:revision current)
     :item-count (:item-count current)
     :current-item-count (count (:current-items current))
     :rule-count (count (:rules current))
     :override-count (count (:overrides current))
     :visible-items visible-items
     :available-kinds (presentation/available-kinds kind-registry)
     :items {:revision (:revision current)
             :count (count (:current-items current))}}))

(defn snapshot
  "Returns a cheap immutable session summary."
  [session]
  (session snapshot-operation nil))

(defn frame
  "Returns the current bounded, presentation-ready UI-neutral frame."
  [session]
  (session frame-operation nil))

(defn document
  "Returns the complete immutable persistent document at the current revision."
  [session]
  (session document-operation nil))

(defn canonical-edn
  "Exports the current document as strict portable canonical EDN."
  [session]
  (workbench/canonical-edn (document session)))

(defn append-item! [session item]
  (session append-item-operation item))

(defn put-domain-rule! [session rule]
  (session put-rule-operation rule))

(defn remove-domain-rule! [session rule-id]
  (session remove-rule-operation rule-id))

(defn set-item-kind! [session override]
  (session set-kind-operation override))

(defn clear-item-kind! [session coordinate]
  (when-not (exact-keys? coordinate #{:item-id :source-revision})
    (reject! :invalid-coordinate
             (when (map? coordinate) (set (keys coordinate)))))
  (session clear-kind-operation coordinate))

(defn- publish! [event]
  (try (tap> event) (catch :default _ nil))
  nil)

(defn- commit! [state lock operation transform]
  (let [result
        (locking lock
          (let [before (:document @state)
                after (transform before)
                snapshot (workbench/snapshot after)]
            (swap! state assoc :document after)
            {:kind :jolt.sim.kind/workbench-change
             :operation operation
             :revision (:revision snapshot)}))]
    (publish! result)
    result))

(defn- navigate-items [state lock token]
  (when-not (exact-keys? token #{:revision :count})
    (reject! :invalid-navigation-token
             (when (map? token) (set (keys token)))))
  (let [captured (locking lock @state)
        snapshot (snapshot-state captured)]
      (when-not (= (:revision token) (:revision snapshot))
        (reject! :stale-navigation
                 {:expected (:revision token)
                  :actual (:revision snapshot)}))
      (when-not (= (:count token) (:current-item-count snapshot))
        (reject! :invalid-navigation-count
                 {:expected (:count token)
                  :actual (:current-item-count snapshot)}))
      ;; Render trusted presentations from the immutable captured document
      ;; after releasing the mutation lock. A slow or blocked presenter must
      ;; not delay an unrelated append or turn its already-definite producer
      ;; operation into an ambiguous acknowledgment.
      (:items (frame-state captured))))

(defn start
  "Starts one live workbench session from optional persistent state.

  Config is closed and may contain `:document`, trusted `:kind-registry`, and
  `:visible-items` (1..1024). The returned opaque capability owns no browser,
  file, child process, or application resource."
  ([] (start {}))
  ([config]
   (let [{:keys [document kind-registry visible-items]}
         (validate-config! config)
         state (atom {:document document
                      :kind-registry kind-registry
                      :visible-items visible-items})
         lock (Object.)]
     (reify
       clojure.lang.IFn
       (invoke [_ operation argument]
         (cond
           (identical? operation snapshot-operation) (snapshot-state @state)
           ;; The atom dereference is one coherent immutable state capture.
           ;; Presentation is advisory and intentionally runs outside the
           ;; mutation lock.
           (identical? operation frame-operation) (frame-state @state)
           (identical? operation document-operation) (:document @state)
           (identical? operation append-item-operation)
           (commit! state lock :item/append
                    #(workbench/append-item % argument))
           (identical? operation put-rule-operation)
           (commit! state lock :rule/put
                    #(workbench/put-domain-rule % argument))
           (identical? operation remove-rule-operation)
           (commit! state lock :rule/remove
                    #(workbench/remove-domain-rule % argument))
           (identical? operation set-kind-operation)
           (commit! state lock :override/set
                    #(workbench/set-item-kind % argument))
           (identical? operation clear-kind-operation)
           (commit! state lock :override/clear
                    #(workbench/clear-item-kind
                      % (:item-id argument) (:source-revision argument)))
           :else (reject! :invalid-operation nil)))

       protocols/Datafiable
       (datafy [this]
         (snapshot this))

       protocols/Navigable
       (nav [_ key value]
         (if (= :items key)
           (navigate-items state lock value)
           value))))))
