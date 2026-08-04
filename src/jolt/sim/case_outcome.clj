(ns jolt.sim.case-outcome
  "The versioned, closed Case/Outcome EDN contract (v1) for one exploration
  case.

  One document pairs the closed description of a single case -- which marked
  scenario, under which execution mode, with which ordinary input value, and
  optionally driven by which exact future schedule -- with the outcome that
  case produced and the ordered monitor decisions observed for it. This is
  the generic data layer that fresh-process exploration workers (for example,
  the whole HTTP/SQLite/TCP outbox Hegel lanes) emit and the static reporter
  later consumes. The namespace owns only the document shape, fail-closed
  validation, byte-stable EDN, and ordinary-value restoration; it adds no
  process supervisor, report renderer, journal, or scenario schema.

  Document storage form (exactly these four top-level keys):

    {:jolt.sim.case-outcome/version 1
     :jolt.sim.case-outcome/case
     {:scenario namespaced-symbol
      :mode :real | :hermetic | :hybrid
      :input <jolt.sim.trace canonical form>
      :schedule nil | exact jolt.sim.future-schedule permutation}
     :jolt.sim.case-outcome/outcome <closed discriminated map, below>
     :jolt.sim.case-outcome/monitors [<stored monitor decision> ...]}

  Outcomes by :status (exact keys per status, no others):

    :completed    {:status :completed :value <canonical>
                   :exit nonnegative-integer}
    :failed       {:status :failed :error <canonical>
                   :exit nonnegative-integer}
    :timeout      {:status :timeout :reason :deadline :exit integer}
    :worker-error {:status :worker-error :error <canonical>
                   :exit nil | integer}

  The constructor accepts the ordinary counterparts: `:completed` carries
  ordinary `:result` (stored as canonical `:value`), `:failed` and
  `:worker-error` carry ordinary `:error` (stored canonical), and `:timeout`
  is already ordinary throughout.

  Stored monitor decisions keep caller order and carry exactly
  {:id <canonical> :status :pass | :violation | :inconclusive
   :detail <canonical> :index nil | nonnegative-integer}. Two decisions in
  one document may not share the same canonical id; ordinary ids that are
  `=` but canonically distinct (for example, a list versus a vector) do not
  collide.

  There is deliberately no compatibility layer: version 1 is the only
  accepted prerelease version. Every ordinary caller value is stored through
  jolt.sim.trace/canonical-value and restored through
  jolt.sim.trace/restore-value (byte arrays come back freshly allocated), and
  schedules are validated by jolt.sim.future-schedule; this namespace reuses
  those two contracts rather than inventing another value codec.

  Validation fails closed with typed ex-info errors tagged `invalid-document`
  carrying a keyword `:reason` and ordinary-data `:detail`. Arbitrary rejected
  host objects are never retained in error data: non-ordinary values are
  reduced to their class-name string. Values outside the trace domain at
  construction (metadata, functions, records, host objects) instead surface
  jolt.sim.trace/unsupported-value, exactly as jolt.sim.monitor does."
  (:require [clojure.edn :as edn]
            [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.trace :as trace]))

(def version
  "The only Case/Outcome document version this namespace accepts."
  1)

(def invalid-document
  "Type value for a malformed Case/Outcome document: wrong shape, wrong
  keys, unsupported version, malformed canonical fields, an invalid
  schedule, invalid monitor decisions, metadata, or unreadable/trailing
  EDN."
  ::invalid-document)

(def ^:private version-key :jolt.sim.case-outcome/version)
(def ^:private case-key :jolt.sim.case-outcome/case)
(def ^:private outcome-key :jolt.sim.case-outcome/outcome)
(def ^:private monitors-key :jolt.sim.case-outcome/monitors)

(def ^:private document-keys
  #{version-key case-key outcome-key monitors-key})

(def ^:private case-keys
  #{:scenario :mode :input :schedule})

(def ^:private modes
  #{:real :hermetic :hybrid})

(def ^:private outcome-statuses
  #{:completed :failed :timeout :worker-error})

(def ^:private monitor-keys
  #{:id :status :detail :index})

(def ^:private monitor-statuses
  #{:pass :violation :inconclusive})

(def ^:private diagnostic-depth-limit 4)
(def ^:private diagnostic-collection-limit 16)

(defn- safe-value
  "Reduces an arbitrary rejected value to bounded ordinary data safe to retain
  in error details. Concrete maps, sets, and vectors are rebuilt without
  metadata up to small depth/width limits. Lazy or otherwise arbitrary
  sequences are never realized merely to explain why a document was rejected;
  functions, byte arrays, records, and host objects become class-name strings."
  ([value]
   (safe-value value 0))
  ([value depth]
   (cond
     (nil? value)
     nil

     (boolean? value)
     value

     (number? value)
     value

     (string? value)
     value

     (char? value)
     value

     (keyword? value)
     value

     (symbol? value)
     ;; Rebuild instead of returning the original symbol: symbols may carry
     ;; metadata, and rejected metadata may itself contain a host object.
     (if-let [ns (namespace value)]
       (symbol ns (name value))
       (symbol (name value)))

     (>= depth diagnostic-depth-limit)
     (str (class value))

     (record? value)
     (str (class value))

     (map? value)
     (into {}
           (map (fn [[entry-key entry-value]]
                  [(safe-value entry-key (inc depth))
                   (safe-value entry-value (inc depth))]))
           (take diagnostic-collection-limit value))

     (set? value)
     (into #{}
           (map #(safe-value % (inc depth)))
           (take diagnostic-collection-limit value))

     (vector? value)
     (mapv #(safe-value % (inc depth))
           (take diagnostic-collection-limit value))

     (sequential? value)
     ;; A LazySeq can be infinite or throw while being realized. Its class is
     ;; enough diagnostic context; validation must still return promptly.
     (str (class value))

     :else
     (str (class value)))))

(defn- malformed-document! [reason detail]
  (throw
   (ex-info
    "Case/Outcome document is malformed"
    {:type invalid-document
     :reason reason
     :detail (safe-value detail)})))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- namespaced-symbol? [value]
  (and (symbol? value) (some? (namespace value))))

(defn- ensure-no-metadata!
  "Deep fail-closed metadata rejection over the ordinary containers of a
  stored document. jolt.sim.trace/canonical-value rejects metadata inside
  canonical projections; the structural maps, vectors, symbols, and
  schedules stored directly need the same guarantee, including documents
  hand-built or read from EDN rather than produced by `document`."
  [value path]
  (when (some? (meta value))
    (malformed-document! :metadata {:path path}))
  (cond
    (map? value)
    (doseq [[entry-key entry-value] value]
      (ensure-no-metadata! entry-key (conj path :map-key))
      (ensure-no-metadata! entry-value (conj path entry-key)))

    (or (vector? value) (sequential? value) (set? value))
    (doseq [[index entry] (map-indexed vector value)]
      (ensure-no-metadata! entry (conj path index)))))

(defn- ensure-no-structure-metadata! [value path]
  (when (some? (meta value))
    (malformed-document! :metadata {:path path})))

;; Case

(defn- validate-case! [value]
  (when-not (map? value)
    (malformed-document! :case-not-a-map {:class (str (class value))}))
  (when-not (= case-keys (set (keys value)))
    (malformed-document! :case-wrong-keys (set (keys value))))
  (let [{:keys [scenario mode input schedule]} value]
    (when-not (namespaced-symbol? scenario)
      (malformed-document! :invalid-scenario scenario))
    (when-not (contains? modes mode)
      (malformed-document! :invalid-mode mode))
    (when-not (trace/canonical-form? input)
      (malformed-document! :invalid-input input))
    (when-not (or (nil? schedule) (future-schedule/valid-schedule? schedule))
      (malformed-document! :invalid-schedule schedule)))
  value)

(defn- case-document [value]
  (when-not (map? value)
    (malformed-document! :case-not-a-map {:class (str (class value))}))
  (ensure-no-structure-metadata! value [case-key])
  (when-not (= case-keys (set (keys value)))
    (malformed-document! :case-wrong-keys (set (keys value))))
  (let [{:keys [scenario mode input schedule]} value]
    (when-not (namespaced-symbol? scenario)
      (malformed-document! :invalid-scenario scenario))
    (when-not (contains? modes mode)
      (malformed-document! :invalid-mode mode))
    (when-not (or (nil? schedule) (future-schedule/valid-schedule? schedule))
      (malformed-document! :invalid-schedule schedule))
    {:scenario scenario
     :mode mode
     :input (trace/canonical-value input [case-key :input])
     :schedule schedule}))

;; Outcome

(defn- check-outcome-keys! [value status expected]
  (when-not (= expected (set (keys value)))
    (malformed-document! :outcome-wrong-keys
                         {:status status :keys (set (keys value))})))

(defn- check-outcome-exit! [exit predicate status]
  (when-not (predicate exit)
    (malformed-document! :invalid-outcome-exit
                         {:status status :exit exit})))

(defn- validate-outcome! [value]
  (when-not (map? value)
    (malformed-document! :outcome-not-a-map {:class (str (class value))}))
  (let [status (:status value)]
    (when-not (contains? outcome-statuses status)
      (malformed-document! :invalid-outcome-status status))
    (case status
      :completed
      (do
        (check-outcome-keys! value status #{:status :value :exit})
        (check-outcome-exit! (:exit value) non-negative-integer? status)
        (when-not (trace/canonical-form? (:value value))
          (malformed-document! :invalid-outcome-value
                               {:status status :value (:value value)})))

      :failed
      (do
        (check-outcome-keys! value status #{:status :error :exit})
        (check-outcome-exit! (:exit value) non-negative-integer? status)
        (when-not (trace/canonical-form? (:error value))
          (malformed-document! :invalid-outcome-error
                               {:status status :error (:error value)})))

      :timeout
      (do
        (check-outcome-keys! value status #{:status :reason :exit})
        (when-not (= :deadline (:reason value))
          (malformed-document! :invalid-outcome-reason (:reason value)))
        (check-outcome-exit! (:exit value) integer? status))

      :worker-error
      (do
        (check-outcome-keys! value status #{:status :error :exit})
        (check-outcome-exit! (:exit value)
                             (fn [exit] (or (nil? exit) (integer? exit)))
                             status)
        (when-not (trace/canonical-form? (:error value))
          (malformed-document! :invalid-outcome-error
                               {:status status :error (:error value)})))))
  value)

(defn- outcome-document [value]
  (when-not (map? value)
    (malformed-document! :outcome-not-a-map {:class (str (class value))}))
  (ensure-no-structure-metadata! value [outcome-key])
  (let [status (:status value)]
    (case status
      :completed
      (do
        (check-outcome-keys! value status #{:status :result :exit})
        (check-outcome-exit! (:exit value) non-negative-integer? status)
        {:status :completed
         :value (trace/canonical-value (:result value) [outcome-key :result])
         :exit (:exit value)})

      :failed
      (do
        (check-outcome-keys! value status #{:status :error :exit})
        (check-outcome-exit! (:exit value) non-negative-integer? status)
        {:status :failed
         :error (trace/canonical-value (:error value) [outcome-key :error])
         :exit (:exit value)})

      :timeout
      (do
        (check-outcome-keys! value status #{:status :reason :exit})
        (when-not (= :deadline (:reason value))
          (malformed-document! :invalid-outcome-reason (:reason value)))
        (check-outcome-exit! (:exit value) integer? status)
        {:status :timeout
         :reason :deadline
         :exit (:exit value)})

      :worker-error
      (do
        (check-outcome-keys! value status #{:status :error :exit})
        (check-outcome-exit! (:exit value)
                             (fn [exit] (or (nil? exit) (integer? exit)))
                             status)
        {:status :worker-error
         :error (trace/canonical-value (:error value) [outcome-key :error])
         :exit (:exit value)})

      (malformed-document! :invalid-outcome-status status))))

;; Monitors

(defn- check-monitor-shape! [position value]
  (when-not (map? value)
    (malformed-document! :monitor-not-a-map
                         {:position position :class (str (class value))}))
  (when-not (= monitor-keys (set (keys value)))
    (malformed-document! :monitor-wrong-keys
                         {:position position :keys (set (keys value))}))
  (when-not (contains? monitor-statuses (:status value))
    (malformed-document! :invalid-monitor-status
                         {:position position :status (:status value)}))
  (when-not (or (nil? (:index value))
                (non-negative-integer? (:index value)))
    (malformed-document! :invalid-monitor-index
                         {:position position :index (:index value)}))
  value)

(defn- validate-monitor! [position value]
  (check-monitor-shape! position value)
  (when-not (trace/canonical-form? (:id value))
    (malformed-document! :invalid-monitor-id
                         {:position position :id (:id value)}))
  (when-not (trace/canonical-form? (:detail value))
    (malformed-document! :invalid-monitor-detail
                         {:position position :detail (:detail value)}))
  value)

(defn- monitor-document [position value]
  (check-monitor-shape! position value)
  (ensure-no-structure-metadata! value [monitors-key position])
  {:id (trace/canonical-value (:id value) [monitors-key position :id])
   :status (:status value)
   :detail (trace/canonical-value (:detail value) [monitors-key position :detail])
   :index (:index value)})

(defn- ensure-distinct-canonical-ids! [monitors]
  (let [duplicates
        (->> (frequencies (mapv :id monitors))
             (keep (fn [[id occurrences]] (when (> occurrences 1) id)))
             (distinct)
             (vec))]
    (when (seq duplicates)
      (malformed-document! :duplicate-monitor-id {:duplicates duplicates})))
  monitors)

(defn- validate-monitors! [value]
  (when-not (vector? value)
    (malformed-document! :monitors-not-a-vector {:class (str (class value))}))
  (doseq [[position monitor] (map-indexed vector value)]
    (validate-monitor! position monitor))
  (ensure-distinct-canonical-ids! value))

(defn- monitors-document [value]
  (when-not (vector? value)
    (malformed-document! :monitors-not-a-vector {:class (str (class value))}))
  (ensure-no-structure-metadata! value [monitors-key])
  (ensure-distinct-canonical-ids! (vec (map-indexed monitor-document value))))

;; Document

(defn validate-document!
  "Validates a versioned Case/Outcome document: exact top-level keys,
  supported version, deep metadata freedom, the case/outcome/monitor shapes
  described in the namespace docstring (including canonical-form checks on
  every stored projection, future-schedule validity, and fail-closed
  duplicate canonical monitor ids). Returns `value` unchanged."
  [value]
  (when-not (map? value)
    (malformed-document! :not-a-map {:class (str (class value))}))
  ;; Validate the closed shape before walking it. A malformed in-memory
  ;; document may contain a lazy or hostile value; traversing that value merely
  ;; to look for metadata can hang or replace the intended typed rejection.
  (ensure-no-structure-metadata! value [])
  (when-not (= document-keys (set (keys value)))
    (malformed-document! :wrong-keys (set (keys value))))
  (when-not (= version (get value version-key))
    (malformed-document! :unsupported-version (get value version-key)))
  (validate-case! (get value case-key))
  (validate-outcome! (get value outcome-key))
  (validate-monitors! (get value monitors-key))
  ;; All recursively traversed fields now have a validated, finite closed
  ;; shape (canonical vectors, exact structural maps, and valid schedules).
  (ensure-no-metadata! value [])
  value)

(defn document
  "Builds a versioned Case/Outcome document from ordinary case, outcome, and
  monitor decision values.

  `case-map` carries exactly :scenario (namespaced symbol), :mode
  (:real/:hermetic/:hybrid), :input (any ordinary value in the
  jolt.sim.trace domain), and :schedule (nil or an exact
  jolt.sim.future-schedule permutation). `outcome-map` is the ordinary
  discriminated outcome described in the namespace docstring (`:completed`
  uses :result here; the stored field is canonical :value). `monitors` is an
  ordered vector of ordinary {:id :status :detail :index} decisions; :id and
  :detail are stored canonical, and duplicate canonical ids fail closed.
  Ordinary values outside the trace domain throw
  jolt.sim.trace/unsupported-value; every other violation throws
  `invalid-document`."
  [case-map outcome-map monitors]
  (validate-document!
   {version-key version
    case-key (case-document case-map)
    outcome-key (outcome-document outcome-map)
    monitors-key (monitors-document monitors)}))

(defn canonical-edn
  "Returns byte-stable, directly readable EDN for a Case/Outcome document.
  Validates the document fail-closed first, then renders through
  jolt.sim.trace/canonical-edn, so the output is independent of source map
  insertion order."
  [value]
  (validate-document! value)
  (trace/canonical-edn value))

(def ^:private end-of-input
  ;; This sentinel must be impossible for EDN input to reproduce. A keyword
  ;; sentinel lets a trailing form with that same keyword masquerade as EOF.
  (atom nil))

(defn- ensure-one-form! [s]
  (when-not (string? s)
    (malformed-document! :not-a-string {:class (str (class s))}))
  (try
    (let [reader (__string-reader s)
          [first-form _] (read+string reader false end-of-input)
          [trailing-form _] (read+string reader false end-of-input)]
      (when (identical? end-of-input first-form)
        (malformed-document! :unreadable-edn "EOF while reading"))
      (when-not (identical? end-of-input trailing-form)
        (malformed-document! :trailing-edn nil)))
    (catch :default error
      (if (= invalid-document (:type (ex-data error)))
        (throw error)
        (malformed-document! :unreadable-edn (ex-message error))))))

(defn read-edn
  "Reads a versioned Case/Outcome document from the EDN string `s`,
  validating the complete document contract before returning it. Exactly one
  EDN form is required. Throws a typed, fail-closed `invalid-document` error
  on unreadable, empty, or trailing EDN and on any malformed document
  shape."
  [s]
  (ensure-one-form! s)
  (let [value
        (try
          (edn/read-string s)
          (catch :default error
            (malformed-document! :unreadable-edn (ex-message error))))]
    (validate-document! value)))

;; Restoration helpers. Each validates the complete document fail-closed
;; before projecting, so a caller never observes ordinary data drawn from a
;; malformed document. Byte arrays and collection containers come back
;; freshly allocated through jolt.sim.trace/restore-value.

(defn restore-case
  "Returns the ordinary case map of a validated document:
  {:scenario symbol :mode keyword :input ordinary-value :schedule vector-or-nil},
  in the exact shape `document` accepts."
  [value]
  (validate-document! value)
  (let [stored (get value case-key)]
    {:scenario (:scenario stored)
     :mode (:mode stored)
     :input (trace/restore-value (:input stored))
     :schedule (:schedule stored)}))

(defn restore-outcome
  "Returns the ordinary outcome map of a validated document, in the exact
  shape `document` accepts: :completed exposes ordinary :result, :failed and
  :worker-error expose ordinary :error, and :timeout is returned unchanged."
  [value]
  (validate-document! value)
  (let [stored (get value outcome-key)]
    (case (:status stored)
      :completed
      {:status :completed
       :result (trace/restore-value (:value stored))
       :exit (:exit stored)}

      :failed
      {:status :failed
       :error (trace/restore-value (:error stored))
       :exit (:exit stored)}

      :timeout
      {:status :timeout
       :reason (:reason stored)
       :exit (:exit stored)}

      :worker-error
      {:status :worker-error
       :error (trace/restore-value (:error stored))
       :exit (:exit stored)})))

(defn restore-monitors
  "Returns the ordered vector of ordinary monitor decisions of a validated
  document, in the exact {:id :status :detail :index} shape `document`
  accepts and jolt.sim.monitor/run-monitor produces."
  [value]
  (validate-document! value)
  (mapv (fn [stored]
          {:id (trace/restore-value (:id stored))
           :status (:status stored)
           :detail (trace/restore-value (:detail stored))
           :index (:index stored)})
        (get value monitors-key)))
