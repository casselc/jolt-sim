(ns jolt.sim.maelstrom.official-run
  "Closed retained evidence for one official Maelstrom run.

  This namespace projects evidence already produced by Maelstrom. It does not
  run, replay, simulate, or independently validate a workload. Complete raw
  artifacts remain authoritative; a document carries a bounded, explicitly
  truncated-or-complete history projection for REPL, tap>, Ripple, reports,
  and future UI consumers."
  (:require [clojure.core.protocols :as protocols]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [jolt.sim.trace :as trace]))

(def version 1)
(def invalid-document ::invalid-document)
(def invalid-cursor ::invalid-cursor)
(def max-captured-operations 4096)
(def max-operation-bytes 16384)
(def max-context-bytes 16384)
(def max-page-operations 32)
(def max-page-operation-bytes 131072)
(def max-artifacts 64)
(def max-artifact-name-bytes 255)
(def page-type :jolt.sim.maelstrom.official-run/page)
(def page-kind :jolt.sim.kind/maelstrom-official-run-page)

(def ^:private version-key :jolt.sim.maelstrom.official-run/version)
(def ^:private run-key :jolt.sim.maelstrom.official-run/run)
(def ^:private outcome-key :jolt.sim.maelstrom.official-run/outcome)
(def ^:private history-key :jolt.sim.maelstrom.official-run/history)
(def ^:private artifacts-key :jolt.sim.maelstrom.official-run/artifacts)
(def ^:private document-keys
  #{version-key run-key outcome-key history-key artifacts-key})
(def ^:private run-keys #{:profile :workload :parameters})
(def ^:private outcome-keys
  #{:status :exit :official-valid? :workload-valid? :checks :stats})
(def ^:private history-keys
  #{:total-count :captured-count :truncated? :artifact :operations})
(def ^:private operation-keys #{:index :type :f :process :time :value})
(def ^:private artifact-keys #{:name :role :bytes :sha256})
(def ^:private statuses #{:passed :failed :timeout :error})
(def ^:private validity-states #{true false :unknown nil})
(def ^:private operation-types #{:invoke :ok :info :fail})
(def ^:private artifact-pattern #"[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
(def ^:private sha256-pattern #"[0-9a-f]{64}")

(defn- invalid! [reason]
  (throw (ex-info "Official Maelstrom run document is malformed"
                  {:type invalid-document :reason reason})))

(defn- utf8-bytes [value]
  (alength (.getBytes value "UTF-8")))

(defn- exact-map? [expected-keys value]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- bounded-canonical-map? [value]
  (and (trace/canonical-form? value)
       (= :jolt.sim.value/map (first value))
       (<= (utf8-bytes (trace/canonical-edn value)) max-context-bytes)))

(defn- logical-name? [value]
  (and (string? value)
       (<= (utf8-bytes value) max-artifact-name-bytes)
       (boolean (re-matches artifact-pattern value))
       (not-any? #{"." ".."} (str/split value #"/"))))

(defn- metadata-free? [value]
  (and (nil? (meta value))
       (cond
         (map? value)
         (every? (fn [[k v]] (and (metadata-free? k) (metadata-free? v)))
                 value)
         (or (vector? value) (list? value) (set? value))
         (every? metadata-free? value)
         :else true)))

(defn- valid-run? [run]
  (and (exact-map? run-keys run)
       (keyword? (:profile run))
       (keyword? (:workload run))
       (bounded-canonical-map? (:parameters run))))

(defn- valid-outcome? [outcome]
  (and (exact-map? outcome-keys outcome)
       (contains? statuses (:status outcome))
       (or (nil? (:exit outcome)) (integer? (:exit outcome)))
       (contains? validity-states (:official-valid? outcome))
       (contains? validity-states (:workload-valid? outcome))
       (bounded-canonical-map? (:checks outcome))
       (bounded-canonical-map? (:stats outcome))
       (or (not= :passed (:status outcome))
           (and (integer? (:exit outcome))
                (zero? (:exit outcome))
                (true? (:official-valid? outcome))
                (true? (:workload-valid? outcome))
                (seq (second (:checks outcome)))))))

(defn- valid-operation? [position operation]
  (and (exact-map? operation-keys operation)
       (= position (:index operation))
       (contains? operation-types (:type operation))
       (keyword? (:f operation))
       (or (nil? (:process operation))
           (= :nemesis (:process operation))
           (non-negative-integer? (:process operation)))
       (integer? (:time operation))
       (trace/canonical-form? (:value operation))
       (<= (utf8-bytes (trace/canonical-edn operation)) max-operation-bytes)))

(defn- valid-artifact? [artifact]
  (and (exact-map? artifact-keys artifact)
       (logical-name? (:name artifact))
       (keyword? (:role artifact))
       (non-negative-integer? (:bytes artifact))
       (string? (:sha256 artifact))
       (boolean (re-matches sha256-pattern (:sha256 artifact)))))

(defn validate-document!
  "Returns a valid closed v1 official-run document, otherwise throws.

  Profile, workload, parameters, checks, and stats remain open data. The
  envelope, outcome, bounded history-capture facts, and artifact identities
  are closed. Shape and bounds are established before recursive metadata
  inspection, so hostile unbounded input is rejected without realization."
  [value]
  (when-not (exact-map? document-keys value) (invalid! :wrong-document-shape))
  (when-not (= version (get value version-key)) (invalid! :unsupported-version))
  (when-not (valid-run? (get value run-key)) (invalid! :invalid-run))
  (when-not (valid-outcome? (get value outcome-key)) (invalid! :invalid-outcome))
  (let [history (get value history-key)
        operations (:operations history)]
    (when-not (and (exact-map? history-keys history)
                   (non-negative-integer? (:total-count history))
                   (non-negative-integer? (:captured-count history))
                   (vector? operations)
                   (= (:captured-count history) (count operations))
                   (<= (:captured-count history)
                       (:total-count history))
                   (<= (:captured-count history) max-captured-operations)
                   (= (:truncated? history)
                      (< (:captured-count history) (:total-count history)))
                   (logical-name? (:artifact history))
                   (every? true? (map-indexed valid-operation? operations)))
      (invalid! :invalid-history)))
  (let [artifacts (get value artifacts-key)]
    (when-not (and (vector? artifacts)
                   (<= (count artifacts) max-artifacts)
                   (every? valid-artifact? artifacts)
                   (= (count artifacts) (count (set (map :name artifacts))))
                   (= :history
                      (:role
                       (first
                        (filter #(= (:artifact (get value history-key))
                                    (:name %))
                                artifacts)))))
      (invalid! :invalid-artifacts)))
  ;; All traversed structures are now exact maps or bounded vectors whose
  ;; leaves are validated canonical forms and scalars.
  (when-not (metadata-free? value) (invalid! :metadata))
  value)

(defn document
  "Builds a closed official-run document from ordinary data.

  `history` must be a map with :total-count, :truncated?, :artifact, and a
  bounded vector :operations. Operation indices are normalized to capture
  order. `artifacts` contains logical {:name :role :bytes :sha256}
  descriptors; no host paths are retained."
  [run outcome history artifacts]
  (let [operations (:operations history)]
    (when-not (and (vector? operations)
                   (<= (count operations) max-captured-operations))
      (invalid! :invalid-operation-input))
    (let [stored-operations
          (mapv (fn [position operation]
                  (assoc operation :index position
                         :value (trace/canonical-value (:value operation))))
                (range)
                operations)]
      (validate-document!
       {version-key version
        run-key (assoc run :parameters
                       (trace/canonical-value (:parameters run)))
        outcome-key (-> outcome
                        (update :checks trace/canonical-value)
                        (update :stats trace/canonical-value))
        history-key (assoc history
                           :captured-count (count stored-operations)
                           :operations stored-operations)
        artifacts-key artifacts}))))

(defn canonical-edn [value]
  (trace/canonical-edn (validate-document! value)))

(def ^:private end-of-input (atom nil))

(defn read-edn [text]
  (when-not (string? text) (invalid! :not-a-string))
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-input)
          [trailing _] (read+string reader false end-of-input)]
      (when (identical? value end-of-input) (invalid! :empty-input))
      (when-not (identical? trailing end-of-input) (invalid! :trailing-input))
      (validate-document! (edn/read-string text)))
    (catch :default error
      (if (= invalid-document (:type (ex-data error)))
        (throw error)
        (invalid! :unreadable-edn)))))

(defn- restore-run [stored]
  (update stored :parameters trace/restore-value))

(defn- restore-outcome [stored]
  (-> stored
      (update :checks trace/restore-value)
      (update :stats trace/restore-value)))

(defn- operation-row [stored]
  {:kind :jolt.sim.kind/maelstrom-operation
   :index (:index stored)
   :type (:type stored)
   :f (:f stored)
   :process (:process stored)
   :time (:time stored)
   :value (trace/restore-value (:value stored))
   :raw stored})

(defn- checked-cursor [cursor total]
  (when-not (and (non-negative-integer? cursor) (<= cursor total))
    (throw (ex-info "Official Maelstrom run cursor is invalid"
                    {:type invalid-cursor :cursor cursor :operation-count total})))
  cursor)

(defn- project-page [document cursor]
  (let [history (get document history-key)
        operations (:operations history)
        total (count operations)
        cursor (checked-cursor cursor total)
        rows (loop [index cursor rows [] bytes 0]
               (if (and (< index total) (< (count rows) max-page-operations))
                 (let [stored (nth operations index)
                       next-bytes (+ bytes (utf8-bytes (trace/canonical-edn stored)))]
                   (if (<= next-bytes max-page-operation-bytes)
                     (recur (inc index) (conj rows (operation-row stored)) next-bytes)
                     rows))
                 rows))
        next-cursor (+ cursor (count rows))
        remaining? (< next-cursor total)]
    {:jolt.sim.maelstrom.official-run/type page-type
     :kind page-kind
     :version version
     :status :ok
     :header (when (zero? cursor)
               {:run (restore-run (get document run-key))
                :outcome (restore-outcome (get document outcome-key))
                :history (dissoc history :operations)
                :artifacts (get document artifacts-key)})
     :cursor cursor
     :next-cursor next-cursor
     :next-page (when remaining? {:cursor next-cursor})
     :captured-count total
     :remaining? remaining?
     :operations rows}))

(defn read-page
  "Returns one bounded immutable page suitable for REPL, tap>, or any UI."
  ([document] (read-page document 0))
  ([document cursor]
   (project-page (validate-document! document) cursor)))

(defn source
  "Returns a datafy/nav source over immutable official-run pages."
  [document]
  (let [document (validate-document! document)]
    (letfn [(page [cursor] (project-page document cursor))]
      (reify
        protocols/Datafiable
        (datafy [_] (page 0))

        protocols/Navigable
        (nav [this key value]
          (if (= :next-page key)
            (if (nil? value)
              nil
              (do
                (when-not (and (map? value)
                               (= #{:cursor} (set (keys value))))
                  (throw (ex-info "Official-run navigation token is invalid"
                                  {:type invalid-cursor})))
                (with-meta (page (:cursor value))
                  {:clojure.datafy/obj this})))
            value))))))
