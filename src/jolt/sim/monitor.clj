(ns jolt.sim.monitor
  "Versioned offline trace documents and a pure fold-based monitor runner.

  A trace document is `{:jolt.sim.trace/version trace-version
  :jolt.sim.trace/events events}`, where `events` is a complete replay trace
  validated by `jolt.sim.kernel/validate-trace!`. Monitors run as a pure fold
  over a validated document's events; see `run-monitor`."
  (:require [clojure.edn :as edn]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.trace :as trace]))

(def trace-version
  "The only trace document version this namespace accepts."
  1)

(def ^:private document-keys
  #{:jolt.sim.trace/version :jolt.sim.trace/events})

(defn- malformed-document! [reason detail]
  (throw
   (ex-info
    "Trace document is malformed"
    {:type ::invalid-document
     :reason reason
     :detail detail})))

(defn- validate-document! [value]
  (when-not (map? value)
    (malformed-document! :not-a-map (str (class value))))
  (when-not (= document-keys (set (keys value)))
    (malformed-document! :wrong-keys (set (keys value))))
  (let [version (:jolt.sim.trace/version value)]
    (when-not (= trace-version version)
      (malformed-document! :unsupported-version version)))
  (kernel/validate-trace! (:jolt.sim.trace/events value))
  value)

(defn document
  "Builds a versioned trace document from `events`, validating exact keys,
  supported version, and the complete event schema before returning it."
  [events]
  (validate-document!
   {:jolt.sim.trace/version trace-version
    :jolt.sim.trace/events events}))

(def ^:private end-of-input ::end-of-input)

(defn- ensure-one-form! [s]
  (when-not (string? s)
    (malformed-document! :not-a-string (str (class s))))
  (try
    (let [reader (__string-reader s)
          [first-form _] (read+string reader false end-of-input)
          [trailing-form _] (read+string reader false end-of-input)]
      (when (= end-of-input first-form)
        (malformed-document! :unreadable-edn "EOF while reading"))
      (when-not (= end-of-input trailing-form)
        (malformed-document! :trailing-edn nil)))
    (catch :default error
      (if (= ::invalid-document (:type (ex-data error)))
        (throw error)
        (malformed-document! :unreadable-edn (ex-message error))))))

(defn read-edn
  "Reads a versioned trace document from the EDN string `s`, validating exact
  keys, supported version, and the complete event schema before returning it.
  Exactly one EDN form is required. Throws a typed, fail-closed error on
  unreadable or trailing EDN and on any malformed document shape."
  [s]
  (ensure-one-form! s)
  (let [value
        (try
          (edn/read-string s)
          (catch :default error
            (malformed-document! :unreadable-edn (ex-message error))))]
    (validate-document! value)))

(def ^:private decision-statuses #{:pass :violation :inconclusive})
(def ^:private monitor-spec-keys #{:id :initial :step :finish})

(defn- fail-closed! [monitor-id reason detail]
  (throw
   (ex-info
    "Monitor produced an invalid result"
    {:type ::invalid-monitor-result
     :monitor monitor-id
     :reason reason
     :detail detail})))

(defn- validate-spec! [spec]
  (when-not (map? spec)
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :not-a-map
       :detail (str (class spec))})))
  (when-not (= monitor-spec-keys (set (keys spec)))
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :wrong-keys
       :detail (set (keys spec))})))
  (when-not (fn? (:step spec))
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :step-not-a-function})))
  (when-not (fn? (:finish spec))
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :finish-not-a-function})))
  (trace/canonical-value (:id spec) [:monitor :id])
  (trace/canonical-value (:initial spec) [:monitor :initial])
  spec)

(defn- decision-keys? [value]
  (contains? #{#{:status} #{:status :detail}} (set (keys value))))

(defn- validate-decision! [monitor-id value]
  (when-not (map? value)
    (fail-closed! monitor-id :decision-not-a-map (str (class value))))
  (when-not (decision-keys? value)
    (fail-closed! monitor-id :decision-wrong-keys (set (keys value))))
  (when-not (contains? decision-statuses (:status value))
    (fail-closed! monitor-id :decision-bad-status (:status value)))
  (when (contains? value :detail)
    (trace/canonical-value (:detail value) [:detail]))
  value)

(defn- validate-step-result! [monitor-id value]
  (when-not (map? value)
    (fail-closed! monitor-id :step-result-not-a-map (str (class value))))
  (if (= #{:state} (set (keys value)))
    (do
      (trace/canonical-value (:state value) [:state])
      value)
    (validate-decision! monitor-id value)))

(defn run-monitor
  "Folds `spec` over a validated trace `doc`'s events.

  `spec` is a map of:
  * `:id`      an opaque monitor identity, returned unchanged
  * `:initial` the starting monitor state
  * `:step`    `(fn [state index event] result)`, where `result` is either
               `{:state next-state}` or a terminal decision map with
               `:status` (one of `:pass`, `:violation`, `:inconclusive`) and
               optional `:detail`
  * `:finish`  `(fn [final-state] decision)`, called once all events are
               folded without an earlier decision, and must itself return a
               decision

  Returns `{:id monitor-id :status status :detail detail :index index}`,
  where `index` is the event index of the first decision, or `nil` when the
  decision came from `:finish`. Every `:state` and `:detail` value is checked
  with `jolt.sim.trace/canonical-value`, including the initial state before any
  callback runs. Values retain their caller-visible types; serialize the
  result through `jolt.sim.trace/canonical-edn` for byte-stable EDN. Malformed
  specs and step or finish results throw fail-closed typed errors rather than
  being coerced; exceptions `:step` or `:finish` raise propagate uncaught
  rather than becoming a decision."
  [spec doc]
  (validate-document! doc)
  (validate-spec! spec)
  (let [monitor-id (:id spec)
        step (:step spec)
        finish (:finish spec)
        events (:jolt.sim.trace/events doc)
        event-count (count events)]
    (loop [state (:initial spec)
           index 0]
      (if (= index event-count)
        (let [decision (validate-decision! monitor-id (finish state))]
          {:id monitor-id
           :status (:status decision)
           :detail (:detail decision)
           :index nil})
        (let [result (validate-step-result!
                      monitor-id (step state index (nth events index)))]
          (if (contains? result :state)
            (recur (:state result) (inc index))
            {:id monitor-id
             :status (:status result)
             :detail (:detail result)
             :index index}))))))
