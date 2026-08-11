(ns jolt.sim.workbench
  "Pure, UI-neutral workspace documents for Ripple and other REPL clients.

  A document is one closed, versioned EDN value whose append-only journal is
  authoritative. Items retain immutable canonical source values and
  provenance. Presentation rules and exact item overrides are overlays; they
  never rewrite source evidence. Replaying the journal derives current state,
  so a persisted document cannot disagree with its own history.

  V1 deliberately contains no renderer functions, global registry, browser
  state, file I/O, or authority to evaluate code. A caller may save the result
  of canonical-edn and restore it with read-edn. Crash-safe file publication is
  a separate storage concern."
  (:require [clojure.edn :as edn]
            [jolt.sim.journal :as journal]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.trace :as trace]))

(def invalid-document :jolt.sim.workbench/invalid-document)
(def rejected-command :jolt.sim.workbench/rejected-command)

(def ^:private version 1)
(def ^:private maximum-journal-entries 4096)
(def ^:private maximum-document-bytes (* 4 1024 1024))
(def ^:private maximum-source-bytes (* 256 1024))
(def ^:private maximum-id-length 128)
(def ^:private maximum-priority 1000000)
(def ^:private fingerprint-algorithm :jolt.sim.fingerprint/crc32c-v1)
(def ^:private raw-kind :jolt.sim.kind/raw-value)

(def ^:private document-keys
  #{:jolt.sim.workbench/version :jolt.sim.workbench/journal})
(def ^:private provenance-keys #{:producer :coordinate})
(def ^:private item-input-keys
  #{:id :source-revision :value :schema-id :suggested-kind :provenance})
(def ^:private rule-input-keys
  #{:id :selector :kind :priority :enabled? :provenance})
(def ^:private override-input-keys
  #{:item-id :source-revision :source-fingerprint :kind :provenance})
(def ^:private source-kind-selector-keys #{:source-kind})
(def ^:private schema-id-selector-keys #{:schema-id})
(def ^:private fingerprint-keys #{:algorithm :bytes :crc32c})

(defn- invalid! [reason detail]
  (throw (ex-info "Invalid jolt-sim workbench document"
                  {:type invalid-document :reason reason :detail detail})))

(defn- reject! [reason detail]
  (throw (ex-info "jolt-sim workbench rejected a command"
                  {:type rejected-command :reason reason :detail detail})))

(defn- exact-map? [value keys]
  (and (map? value) (not (seq (meta value)))
       (= (count keys) (count value))
       (every? #(contains? value %) keys)))

(defn- namespaced-keyword? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- bounded-id? [value]
  (and (string? value) (pos? (count value))
       (<= (count value) maximum-id-length)))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- canonical-bytes [canonical]
  (.getBytes (trace/canonical-edn canonical) "UTF-8"))

(defn- source-fingerprint [canonical]
  (let [bytes (canonical-bytes canonical)]
    {:algorithm fingerprint-algorithm
     :bytes (alength bytes)
     :crc32c (journal/crc32c bytes)}))

(defn- valid-fingerprint? [value]
  (and (exact-map? value fingerprint-keys)
       (= fingerprint-algorithm (:algorithm value))
       (non-negative-integer? (:bytes value))
       (<= (:bytes value) maximum-source-bytes)
       (integer? (:crc32c value))
       (<= 0 (:crc32c value) 0xffffffff)))

(defn- canonical-provenance [value]
  (when-not (exact-map? value provenance-keys)
    (reject! :invalid-provenance-shape
             (when (map? value) (set (keys value)))))
  (when-not (namespaced-keyword? (:producer value))
    (reject! :invalid-producer (:producer value)))
  {:producer (:producer value)
   :coordinate (trace/canonical-value (:coordinate value)
                                      [:workbench :provenance :coordinate])})

(defn- inferred-source-kind [value]
  (let [candidate (when (map? value) (:kind value))]
    (when (namespaced-keyword? candidate) candidate)))

(defn- canonical-item [input]
  (when-not (exact-map? input item-input-keys)
    (reject! :invalid-item-shape (when (map? input) (set (keys input)))))
  (when-not (bounded-id? (:id input))
    (reject! :invalid-item-id (:id input)))
  (when-not (non-negative-integer? (:source-revision input))
    (reject! :invalid-source-revision (:source-revision input)))
  (doseq [key [:schema-id :suggested-kind]]
    (when-not (or (nil? (get input key))
                  (namespaced-keyword? (get input key)))
      (reject! (keyword (str "invalid-" (name key))) (get input key))))
  (let [value (trace/canonical-value (:value input) [:workbench :item :value])
        fingerprint (source-fingerprint value)]
    (when (> (:bytes fingerprint) maximum-source-bytes)
      (reject! :source-too-large {:bytes (:bytes fingerprint)
                                  :maximum maximum-source-bytes}))
    {:id (:id input)
     :source-revision (:source-revision input)
     :source-kind (inferred-source-kind (trace/restore-value value))
     :schema-id (:schema-id input)
     :suggested-kind (:suggested-kind input)
     :source-fingerprint fingerprint
     :value value
     :provenance (canonical-provenance (:provenance input))}))

(defn- canonical-selector [selector]
  (cond
    (exact-map? selector source-kind-selector-keys)
    (do (when-not (namespaced-keyword? (:source-kind selector))
          (reject! :invalid-source-kind-selector (:source-kind selector)))
        selector)

    (exact-map? selector schema-id-selector-keys)
    (do (when-not (namespaced-keyword? (:schema-id selector))
          (reject! :invalid-schema-id-selector (:schema-id selector)))
        selector)

    :else
    (reject! :invalid-selector-shape
             (when (map? selector) (set (keys selector))))))

(defn- canonical-rule [input revision]
  (when-not (exact-map? input rule-input-keys)
    (reject! :invalid-rule-shape (when (map? input) (set (keys input)))))
  (when-not (bounded-id? (:id input))
    (reject! :invalid-rule-id (:id input)))
  (when-not (namespaced-keyword? (:kind input))
    (reject! :invalid-presentation-kind (:kind input)))
  (when-not (and (integer? (:priority input))
                 (<= (- maximum-priority) (:priority input) maximum-priority))
    (reject! :invalid-rule-priority (:priority input)))
  (when-not (boolean? (:enabled? input))
    (reject! :invalid-rule-enabled (:enabled? input)))
  {:id (:id input)
   :selector (canonical-selector (:selector input))
   :kind (:kind input)
   :priority (:priority input)
   :enabled? (:enabled? input)
   :updated-revision revision
   :provenance (canonical-provenance (:provenance input))})

(defn- coordinate [item]
  {:item-id (:id item) :source-revision (:source-revision item)})

(defn- coordinate-key [item-id source-revision]
  [item-id source-revision])

(defn- item-key [item]
  (coordinate-key (:id item) (:source-revision item)))

(defn- find-item [state item-id source-revision]
  (get (:items state) (coordinate-key item-id source-revision)))

(defn- canonical-override [state input revision]
  (when-not (exact-map? input override-input-keys)
    (reject! :invalid-override-shape
             (when (map? input) (set (keys input)))))
  (when-not (bounded-id? (:item-id input))
    (reject! :invalid-item-id (:item-id input)))
  (when-not (non-negative-integer? (:source-revision input))
    (reject! :invalid-source-revision (:source-revision input)))
  (when-not (valid-fingerprint? (:source-fingerprint input))
    (reject! :invalid-source-fingerprint (:source-fingerprint input)))
  (when-not (namespaced-keyword? (:kind input))
    (reject! :invalid-presentation-kind (:kind input)))
  (let [item (find-item state (:item-id input) (:source-revision input))]
    (when-not item
      (reject! :unknown-item {:item-id (:item-id input)
                              :source-revision (:source-revision input)}))
    (when-not (= (:source-fingerprint item) (:source-fingerprint input))
      (reject! :stale-source {:item-id (:item-id input)
                              :source-revision (:source-revision input)}))
    {:item-id (:item-id input)
     :source-revision (:source-revision input)
     :source-fingerprint (:source-fingerprint item)
     :kind (:kind input)
     :updated-revision revision
     :provenance (canonical-provenance (:provenance input))}))

(defn empty-document
  "Returns an empty persistent workbench document."
  []
  {:jolt.sim.workbench/version version
   :jolt.sim.workbench/journal []})

(defn- empty-state []
  {:revision 0 :items {} :latest {} :rules {} :overrides {}})

(defn- apply-event [state event validating?]
  (let [expected-revision (inc (:revision state))
        revision (:revision event)]
    (when-not (= expected-revision revision)
      ((if validating? invalid! reject!) :noncontiguous-revision
       {:expected expected-revision :actual revision}))
    (case (:op event)
      :item/append
      (let [item (:item event)
            key (item-key item)
            prior-latest (get (:latest state) (:id item))]
        (when (contains? (:items state) key)
          ((if validating? invalid! reject!) :duplicate-item-coordinate
           (coordinate item)))
        (when (and prior-latest
                   (<= (:source-revision item) prior-latest))
          ((if validating? invalid! reject!) :nonmonotonic-source-revision
           {:item-id (:id item)
            :latest prior-latest
            :actual (:source-revision item)}))
        (-> state
            (assoc :revision revision)
            (assoc-in [:items key] item)
            (assoc-in [:latest (:id item)] (:source-revision item))))

      :rule/put
      (-> state
          (assoc :revision revision)
          (assoc-in [:rules (get-in event [:rule :id])] (:rule event)))

      :rule/remove
      (do
        (when-not (contains? (:rules state) (:rule-id event))
          ((if validating? invalid! reject!) :unknown-rule (:rule-id event)))
        (-> state
            (assoc :revision revision)
            (update :rules dissoc (:rule-id event))))

      :override/set
      (let [override (:override event)
            key (coordinate-key (:item-id override)
                                (:source-revision override))]
        (-> state
            (assoc :revision revision)
            (assoc-in [:overrides key] override)))

      :override/clear
      (let [key (coordinate-key (:item-id event) (:source-revision event))]
        (when-not (contains? (:overrides state) key)
          ((if validating? invalid! reject!) :unknown-override
           {:item-id (:item-id event)
            :source-revision (:source-revision event)}))
        (-> state
            (assoc :revision revision)
            (update :overrides dissoc key)))

      ((if validating? invalid! reject!) :unknown-operation (:op event)))))

(defn- event-shape-valid? [event]
  (and (map? event)
       (not (seq (meta event)))
       (case (:op event)
         :item/append (exact-map? event #{:revision :op :item})
         :rule/put (exact-map? event #{:revision :op :rule})
         :rule/remove (exact-map? event #{:revision :op :rule-id})
         :override/set (exact-map? event #{:revision :op :override})
         :override/clear (exact-map? event
                                     #{:revision :op :item-id
                                       :source-revision})
         false)))

(defn- validate-stored-provenance! [value path]
  (when-not (and (exact-map? value provenance-keys)
                 (namespaced-keyword? (:producer value))
                 (trace/canonical-form? (:coordinate value)))
    (invalid! :invalid-provenance path)))

(defn- validate-stored-item! [item]
  (let [keys #{:id :source-revision :source-kind :schema-id :suggested-kind
               :source-fingerprint :value :provenance}]
    (when-not (exact-map? item keys)
      (invalid! :invalid-item-shape (when (map? item) (set (keys item)))))
    (when-not (and (bounded-id? (:id item))
                   (non-negative-integer? (:source-revision item))
                   (or (nil? (:source-kind item))
                       (namespaced-keyword? (:source-kind item)))
                   (or (nil? (:schema-id item))
                       (namespaced-keyword? (:schema-id item)))
                   (or (nil? (:suggested-kind item))
                       (namespaced-keyword? (:suggested-kind item)))
                   (valid-fingerprint? (:source-fingerprint item))
                   (trace/canonical-form? (:value item)))
      (invalid! :invalid-item (:id item)))
    (let [actual (source-fingerprint (:value item))
          restored (trace/restore-value (:value item))]
      (when-not (= actual (:source-fingerprint item))
        (invalid! :source-fingerprint-mismatch (:id item)))
      (when-not (= (inferred-source-kind restored) (:source-kind item))
        (invalid! :source-kind-mismatch (:id item))))
    (validate-stored-provenance! (:provenance item) [:item (:id item)])))

(defn- validate-stored-rule! [rule revision]
  (let [keys #{:id :selector :kind :priority :enabled? :updated-revision
               :provenance}]
    (when-not (exact-map? rule keys)
      (invalid! :invalid-rule-shape (when (map? rule) (set (keys rule)))))
    (when-not (= revision (:updated-revision rule))
      (invalid! :rule-revision-mismatch (:id rule)))
    (when-not (and (bounded-id? (:id rule))
                   (namespaced-keyword? (:kind rule))
                   (integer? (:priority rule))
                   (<= (- maximum-priority)
                       (:priority rule)
                       maximum-priority)
                   (boolean? (:enabled? rule)))
      (invalid! :invalid-rule (:id rule)))
    (try
      (canonical-selector (:selector rule))
      (catch :default error
        (invalid! :invalid-rule-selector
                  (or (:reason (ex-data error)) :unknown))))
    (validate-stored-provenance! (:provenance rule) [:rule (:id rule)])))

(defn- validate-stored-override! [state override revision]
  (let [keys #{:item-id :source-revision :source-fingerprint :kind
               :updated-revision :provenance}]
    (when-not (exact-map? override keys)
      (invalid! :invalid-override-shape
                (when (map? override) (set (keys override)))))
    (when-not (= revision (:updated-revision override))
      (invalid! :override-revision-mismatch
                {:item-id (:item-id override)
                 :source-revision (:source-revision override)}))
    (when-not (and (bounded-id? (:item-id override))
                   (non-negative-integer? (:source-revision override))
                   (valid-fingerprint? (:source-fingerprint override))
                   (namespaced-keyword? (:kind override)))
      (invalid! :invalid-override
                {:item-id (:item-id override)
                 :source-revision (:source-revision override)}))
    (let [item (find-item state (:item-id override)
                          (:source-revision override))]
      (when-not item
        (invalid! :override-item-missing
                  {:item-id (:item-id override)
                   :source-revision (:source-revision override)}))
      (when-not (= (:source-fingerprint item)
                   (:source-fingerprint override))
        (invalid! :override-source-mismatch
                  {:item-id (:item-id override)
                   :source-revision (:source-revision override)})))
    (validate-stored-provenance!
     (:provenance override)
     [:override (:item-id override) (:source-revision override)])))

(defn- replay [document]
  (when-not (exact-map? document document-keys)
    (invalid! :wrong-document-shape
              (when (map? document) (set (keys document)))))
  (when-not (= version (:jolt.sim.workbench/version document))
    (invalid! :unsupported-version (:jolt.sim.workbench/version document)))
  (let [events (:jolt.sim.workbench/journal document)]
    (when-not (and (vector? events) (nil? (meta events))
                   (<= (count events) maximum-journal-entries))
      (invalid! :invalid-journal nil))
    (reduce
     (fn [state event]
       (when-not (event-shape-valid? event)
         (invalid! :invalid-event-shape (when (map? event) (:op event))))
       (when-not (non-negative-integer? (:revision event))
         (invalid! :invalid-revision (:revision event)))
       (case (:op event)
         :item/append (validate-stored-item! (:item event))
         :rule/put (validate-stored-rule! (:rule event) (:revision event))
         :rule/remove (when-not (bounded-id? (:rule-id event))
                        (invalid! :invalid-rule-id (:rule-id event)))
         :override/set (validate-stored-override!
                        state (:override event) (:revision event))
         :override/clear
         (when-not (and (bounded-id? (:item-id event))
                        (non-negative-integer? (:source-revision event)))
           (invalid! :invalid-override-coordinate nil)))
       (apply-event state event true))
     (empty-state)
     events)))

(defn validate-document!
  "Validates a complete document and returns it unchanged."
  [document]
  (replay document)
  (let [bytes (alength (.getBytes (trace/canonical-edn document) "UTF-8"))]
    (when (> bytes maximum-document-bytes)
      (invalid! :document-too-large {:bytes bytes
                                     :maximum maximum-document-bytes})))
  document)

(defn- append-event [document event]
  (let [state (replay document)
        event (assoc event :revision (inc (:revision state)))
        _ (apply-event state event false)
        result (update document :jolt.sim.workbench/journal conj event)]
    (validate-document! result)))

(defn append-item
  "Appends one immutable source item. Source revisions for an ID must increase."
  [document input]
  (append-event document {:op :item/append :item (canonical-item input)}))

(defn put-domain-rule
  "Adds or replaces one persisted domain-to-presentation-kind rule."
  [document input]
  (let [state (replay document)
        revision (inc (:revision state))]
    (append-event document
                  {:op :rule/put :rule (canonical-rule input revision)})))

(defn remove-domain-rule
  "Removes one current rule while retaining the operation in the journal."
  [document rule-id]
  (when-not (bounded-id? rule-id)
    (reject! :invalid-rule-id rule-id))
  (append-event document {:op :rule/remove :rule-id rule-id}))

(defn set-item-kind
  "Sets an exact item override guarded by its immutable source fingerprint."
  [document input]
  (let [state (replay document)
        revision (inc (:revision state))]
    (append-event document
                  {:op :override/set
                   :override (canonical-override state input revision)})))

(defn clear-item-kind
  "Clears an exact item override without changing the source item."
  [document item-id source-revision]
  (when-not (and (bounded-id? item-id)
                 (non-negative-integer? source-revision))
    (reject! :invalid-override-coordinate
             {:item-id item-id :source-revision source-revision}))
  (append-event document {:op :override/clear
                          :item-id item-id
                          :source-revision source-revision}))

(defn snapshot
  "Returns a canonical, datafy-friendly current projection derived by replay."
  [document]
  (let [{:keys [revision items latest rules overrides]} (replay document)
        current-items (->> latest
                           (map (fn [[id source-revision]]
                                  (get items (coordinate-key id source-revision))))
                           (sort-by :id)
                           vec)]
    {:kind :jolt.sim.kind/workbench
     :revision revision
     :item-count (count items)
     :current-items current-items
     :rules (->> (vals rules) (sort-by :id) vec)
     :overrides (->> (vals overrides)
                     (sort-by (juxt :item-id :source-revision)) vec)}))

(defn item
  "Returns one immutable stored item by exact coordinate, or nil."
  [document item-id source-revision]
  (find-item (replay document) item-id source-revision))

(defn- selector-matches? [selector item]
  (cond
    (contains? selector :source-kind)
    (= (:source-kind selector) (:source-kind item))

    (contains? selector :schema-id)
    (= (:schema-id selector) (:schema-id item))

    :else false))

(defn resolve-kind
  "Resolves one item's presentation kind without invoking a renderer.

  Precedence is exact override, highest-priority applicable domain rule (then
  newest rule revision and lexical ID), producer suggestion, and raw EDN."
  [document item-id source-revision]
  (let [state (replay document)
        key (coordinate-key item-id source-revision)
        item (get (:items state) key)]
    (when-not item
      (reject! :unknown-item {:item-id item-id
                              :source-revision source-revision}))
    (if-let [override (get (:overrides state) key)]
      {:kind (:kind override) :source :exact-override}
      (if-let [rule (->> (vals (:rules state))
                         (filter :enabled?)
                         (filter #(selector-matches? (:selector %) item))
                         (sort-by (juxt (comp - :priority)
                                        (comp - :updated-revision)
                                        :id))
                         first)]
        {:kind (:kind rule) :source :domain-rule :rule-id (:id rule)}
        (if-let [kind (:suggested-kind item)]
          {:kind kind :source :producer-default}
          {:kind raw-kind :source :raw})))))

(defn present-item
  "Presents one stored item through its resolved persisted presentation kind.

  `kind-registry` is trusted executable configuration supplied by the caller.
  The returned value keeps the exact immutable item coordinate, the selection
  provenance, and the ordinary data-only presentation model. A missing custom
  renderer fails closed without changing the document or hiding its source."
  [document kind-registry item-id source-revision]
  (let [stored (item document item-id source-revision)]
    (when-not stored
      (reject! :unknown-item {:item-id item-id
                              :source-revision source-revision}))
    (let [selection (resolve-kind document item-id source-revision)]
      {:coordinate {:item-id item-id :source-revision source-revision}
       :source-fingerprint (:source-fingerprint stored)
       :selection selection
       :presentation
       (presentation/present-as-kind
        kind-registry (:kind selection) (trace/restore-value (:value stored)))})))

(defn canonical-edn
  "Returns the validated, byte-stable persistent document representation."
  [document]
  (validate-document! document)
  (trace/canonical-edn document))

(def ^:private end-of-input (Object.))

(defn read-edn
  "Reads exactly one persisted workbench document and validates its replay."
  [text]
  (when-not (string? text)
    (invalid! :not-a-string (str (class text))))
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-input)
          [trailing _] (read+string reader false end-of-input)]
      (when (identical? value end-of-input)
        (invalid! :unreadable-edn "EOF while reading"))
      (when-not (identical? trailing end-of-input)
        (invalid! :trailing-edn nil))
      (validate-document! (edn/read-string text)))
    (catch :default error
      (if (= invalid-document (:type (ex-data error)))
        (throw error)
        (invalid! :unreadable-edn (ex-message error))))))
