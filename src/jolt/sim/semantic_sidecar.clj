(ns jolt.sim.semantic-sidecar
  "A closed semantic-sidecar v1 for one canonical Case/Outcome document.

  The sidecar is deliberately one-way: it embeds the complete canonical
  Case/Outcome EDN string, one separately versioned semantic document, and the
  canonical id of one monitor already stored by that Case/Outcome document.
  Case/Outcome v1 and its monitor schema remain unchanged.

  Semantic grammars and evaluators remain extension-owned. Callers supply
  exactly `{:semantic-validator validate! :evaluator evaluate}`. The validator
  is the existing public validator for the embedded semantic document; this
  namespace assigns no meaning to its payload. The unary evaluator is run
  twice over that validated document. Both canonical results must agree with
  each other and with the uniquely selected stored monitor decision.

  No host PID, path, process coordinate, or content hash is an identity here.
  Timeout and worker-error Case/Outcome documents cannot have a sidecar."
  (:require [clojure.edn :as edn]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.trace :as trace]))

(def version
  "The only semantic-sidecar document version accepted."
  1)

(def invalid-document
  "Type value for a malformed or incoherent semantic-sidecar document."
  ::invalid-document)

(def ^:private version-key :jolt.sim.semantic-sidecar/version)
(def ^:private case-outcome-edn-key
  :jolt.sim.semantic-sidecar/case-outcome-edn)
(def ^:private semantic-document-key
  :jolt.sim.semantic-sidecar/semantic-document)
(def ^:private monitor-id-key :jolt.sim.semantic-sidecar/monitor-id)

(def ^:private document-keys
  #{version-key case-outcome-edn-key semantic-document-key monitor-id-key})

(def ^:private adapter-keys #{:semantic-validator :evaluator})

(defn- malformed! [reason detail]
  (throw
   (ex-info
    "Semantic sidecar is malformed or incoherent"
    {:type invalid-document
     :reason reason
     :detail detail})))

(defn- validate-adapters! [adapters]
  (when-not (map? adapters)
    (malformed! :adapters-not-a-map (str (class adapters))))
  (when-not (= adapter-keys (set (keys adapters)))
    (malformed! :adapter-wrong-keys
                {:expected adapter-keys :actual-count (count (keys adapters))}))
  (when-not (fn? (:semantic-validator adapters))
    (malformed! :semantic-validator-not-a-function nil))
  (when-not (fn? (:evaluator adapters))
    (malformed! :evaluator-not-a-function nil))
  adapters)

(defn- read-canonical-case-outcome! [text]
  (when-not (string? text)
    (malformed! :case-outcome-edn-not-a-string (str (class text))))
  ;; These are the Case/Outcome contract's public reader and canonical writer.
  ;; Validation, including duplicate stored monitor ids, stays with its owner.
  (let [doc (case-outcome/read-edn text)]
    (when-not (= text (case-outcome/canonical-edn doc))
      (malformed! :noncanonical-case-outcome-edn nil))
    doc))

(defn- eligible-outcome! [case-outcome-doc]
  (let [status (:status (case-outcome/restore-outcome case-outcome-doc))]
    (when (contains? #{:timeout :worker-error} status)
      (malformed! :unsupported-outcome-status status))))

(defn- select-monitor! [case-outcome-doc canonical-monitor-id]
  (let [monitors (case-outcome/restore-monitors case-outcome-doc)]
    (when (empty? monitors)
      (malformed! :no-monitors nil))
    (let [selected
          (filterv
           (fn [monitor]
             (= canonical-monitor-id
                (trace/canonical-value (:id monitor) [:monitor :id])))
           monitors)]
      (case (count selected)
        0 (malformed! :unknown-monitor canonical-monitor-id)
        1 (first selected)
        ;; Case/Outcome v1 already rejects this. Retain the local fail-closed
        ;; arm so uniqueness remains explicit if that upstream API remints.
        (malformed! :duplicate-monitor canonical-monitor-id)))))

(defn- evaluate-twice! [evaluator semantic-document stored-monitor]
  (let [first-result
        (trace/canonical-value (evaluator semantic-document)
                               [:evaluator :first])
        second-result
        (trace/canonical-value (evaluator semantic-document)
                               [:evaluator :second])
        stored-result
        (trace/canonical-value stored-monitor [:stored-monitor])]
    (when-not (= first-result second-result)
      (malformed! :nondeterministic-evaluator
                  {:first first-result :second second-result}))
    (when-not (= stored-result first-result)
      (malformed! :evaluator-mismatch
                  {:stored stored-result :evaluated first-result}))))

(defn validate-document!
  "Validates and verifies a semantic-sidecar document, returning it unchanged.

  `adapters` must contain exactly `:semantic-validator`, the existing validator
  for the separately versioned semantic document, and `:evaluator`, a unary
  deterministic evaluator returning the selected monitor's ordinary
  `{:id :status :detail :index}` decision.

  The embedded Case/Outcome text must be exactly the output of
  `jolt.sim.case-outcome/canonical-edn`. Its public reader validates the whole
  Case/Outcome contract. The selected monitor id must occur exactly once, and
  two evaluator runs must be canonically equal to that stored decision."
  [value adapters]
  (validate-adapters! adapters)
  (when-not (map? value)
    (malformed! :not-a-map (str (class value))))
  (when-not (= document-keys (set (keys value)))
    (malformed! :wrong-keys
                {:expected document-keys :actual-count (count (keys value))}))
  (when-not (= version (get value version-key))
    (malformed! :unsupported-version (get value version-key)))
  (let [canonical-monitor-id (get value monitor-id-key)]
    (when-not (trace/canonical-form? canonical-monitor-id)
      (malformed! :invalid-monitor-id nil))
    (let [case-outcome-doc
          (read-canonical-case-outcome! (get value case-outcome-edn-key))
          semantic-document (get value semantic-document-key)]
      (eligible-outcome! case-outcome-doc)
      ;; The extension's existing grammar is the sole semantic payload
      ;; authority. No event tags, positions, case fields, or meanings are
      ;; duplicated or inferred here.
      ((:semantic-validator adapters) semantic-document)
      (let [stored-monitor
            (select-monitor! case-outcome-doc canonical-monitor-id)]
        (evaluate-twice! (:evaluator adapters)
                         semantic-document
                         stored-monitor))))
  value)

(defn document
  "Builds and verifies semantic-sidecar v1.

  `case-outcome-edn` must already be canonical Case/Outcome EDN. `monitor-id`
  is an ordinary id and is stored through `jolt.sim.trace/canonical-value`.
  The semantic document is retained unchanged and interpreted only by the
  supplied adapters."
  [case-outcome-edn semantic-document monitor-id adapters]
  (validate-document!
   {version-key version
    case-outcome-edn-key case-outcome-edn
    semantic-document-key semantic-document
    monitor-id-key (trace/canonical-value monitor-id [monitor-id-key])}
   adapters))

(defn canonical-edn
  "Returns byte-stable EDN for a verified semantic-sidecar document."
  [value adapters]
  (validate-document! value adapters)
  (trace/canonical-edn value))

(def ^:private end-of-input (atom nil))

(defn- ensure-one-form! [text]
  (when-not (string? text)
    (malformed! :not-a-string (str (class text))))
  (try
    (let [reader (__string-reader text)
          [first-form _] (read+string reader false end-of-input)
          [trailing-form _] (read+string reader false end-of-input)]
      (when (identical? end-of-input first-form)
        (malformed! :unreadable-edn "EOF while reading"))
      (when-not (identical? end-of-input trailing-form)
        (malformed! :trailing-edn nil)))
    (catch :default error
      (if (= invalid-document (:type (ex-data error)))
        (throw error)
        (malformed! :unreadable-edn (ex-message error))))))

(defn read-edn
  "Reads exactly one EDN form and verifies it as semantic-sidecar v1."
  [text adapters]
  (ensure-one-form! text)
  (let [value
        (try
          (edn/read-string text)
          (catch :default error
            (malformed! :unreadable-edn (ex-message error))))]
    (validate-document! value adapters)))
