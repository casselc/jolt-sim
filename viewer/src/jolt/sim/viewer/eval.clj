(ns jolt.sim.viewer.eval
  "Opt-in trusted Ripple adapter for a UI-neutral EvalSession.

  This namespace owns only the bounded wire projection and a closure over an
  explicitly supplied EvalSession.  It does not parse HTTP, own authentication,
  create a second evaluator, or let browser input select functions."
  (:require [jolt.sim.eval-session :as eval-session]))

(def ^:private maximum-render-characters 4096)
(def ^:private maximum-output-characters 4096)
(def ^:private maximum-scalar-characters 1024)
(def ^:private maximum-collection-width 16)
(def ^:private maximum-projection-depth 4)
(def ^:private native-floating-class (class 0.0))

(defn- bounded-text [text limit]
  (let [text (str text)]
    (if (> (count text) limit)
      {:text (subs text 0 limit) :truncated? true}
      {:text text :truncated? false})))

(defn- bounded-name [value truncated?]
  (let [ns-text (namespace value)
        name-text (name value)
        combined-length (+ (count name-text)
                           (if ns-text (inc (count ns-text)) 0))]
    (if (<= combined-length maximum-scalar-characters)
      value
      (do
        (reset! truncated? true)
        (str "#<" (if (keyword? value) "keyword " "symbol ")
             (when ns-text
               (str (:text (bounded-text ns-text 256)) "/"))
             (:text (bounded-text name-text 512)) ">")))))

(defn- safe-display-value
  "Copies only bounded eager ordinary data. Lazy/arbitrary sequences, records,
  large exact numbers, functions, and host objects are never printed or
  realized; they become class markers."
  [value depth truncated?]
  (cond
    (nil? value) nil
    (boolean? value) value
    (char? value) value

    (string? value)
    (let [bounded (bounded-text value maximum-scalar-characters)
          text (:text bounded)]
      (when (:truncated? bounded) (reset! truncated? true))
      text)

    (keyword? value) (bounded-name value truncated?)
    (symbol? value) (bounded-name value truncated?)

    (number? value)
    (if (or (= native-floating-class (class value))
            (and (integer? value)
                 (<= Long/MIN_VALUE value Long/MAX_VALUE)))
      value
      (do (reset! truncated? true) (str "#<number " (class value) ">")))

    (>= depth maximum-projection-depth)
    (do (reset! truncated? true) (str "#<" (class value) ">"))

    (bytes? value)
    (let [length (alength ^bytes value)
          width (min length maximum-collection-width)]
      (when (> length width) (reset! truncated? true))
      (mapv #(bit-and 0xff (aget ^bytes value %)) (range width)))

    (record? value)
    (do (reset! truncated? true) (str "#<" (class value) ">"))

    (map? value)
    (do
      (when (> (count value) maximum-collection-width)
        (reset! truncated? true))
      (into {}
            (map (fn [[key entry]]
                   [(safe-display-value key (inc depth) truncated?)
                    (safe-display-value entry (inc depth) truncated?)]))
            (take maximum-collection-width value)))

    (set? value)
    (do
      (when (> (count value) maximum-collection-width)
        (reset! truncated? true))
      (into #{}
            (map #(safe-display-value % (inc depth) truncated?))
            (take maximum-collection-width value)))

    (vector? value)
    (do
      (when (> (count value) maximum-collection-width)
        (reset! truncated? true))
      (mapv #(safe-display-value % (inc depth) truncated?)
            (take maximum-collection-width value)))

    (sequential? value)
    (do (reset! truncated? true) (str "#<" (class value) ">"))

    :else
    (do (reset! truncated? true) (str "#<" (class value) ">"))))

(defn- bounded-value [value]
  (try
    (let [projected? (atom false)
          safe (safe-display-value value 0 projected?)
          rendered (pr-str safe)
          bounded (bounded-text rendered maximum-render-characters)]
      (assoc bounded :truncated? (or @projected? (:truncated? bounded))))
    (catch :default _
      {:text "#<unprintable>" :truncated? false :print-failed? true})))

(defn- event-wire [event]
  (let [tag (:tag event)]
    (case tag
      :out
      (merge {"tag" "out"}
             (let [{:keys [text truncated?]}
                   (bounded-text (:val event) maximum-output-characters)]
               {"text" text "truncated" truncated?}))

      :err
      (merge {"tag" "err"}
             (let [{:keys [text truncated?]}
                   (bounded-text (:val event) maximum-output-characters)]
               {"text" text "truncated" truncated?}))

      :ret
      (let [{:keys [text truncated? print-failed?]}
            (bounded-value (:val event))]
        {"tag" "ret"
         "printedValue" text
         "truncated" truncated?
         "printFailed" (boolean print-failed?)
         "exception" (boolean (:exception event))
         "namespace" (:text (bounded-text (:ns event) 1024))
         "namespaceTruncated" (:truncated? (bounded-text (:ns event) 1024))
         "elapsedMs" (:ms event)})

      ;; EvalSession currently retains only eval-stream's closed event tags.
      ;; Keep this fail-closed if that upstream contract ever expands.
      (throw (ex-info "Ripple evaluation service received an unknown event"
                      {:type ::invalid-evaluation :reason :unknown-event-tag})))))

(defn evaluation-wire
  "Projects one committed EvalSession envelope to bounded JSON-safe data.
  Arbitrary evaluation values cross the adapter only as bounded,
  print-length/print-level-limited display text."
  [envelope]
  (let [before (bounded-text (get-in envelope [:namespace :before]) 1024)
        after (bounded-text (get-in envelope [:namespace :after]) 1024)]
    {"version" 1
     "sequence" (str (:sequence envelope))
     "namespace" {"before" (:text before)
                    "beforeTruncated" (:truncated? before)
                    "after" (:text after)
                    "afterTruncated" (:truncated? after)}
     "events" (mapv event-wire (:events envelope))}))

(defn service
  "Returns the one trusted `(form-string -> bounded-wire-result)` closure used
  by Ripple's optional HTTP adapter.  Each call delegates exactly once to the
  supplied EvalSession. The committed envelope is the complete receipt; this
  closure deliberately does not take a separately racy session snapshot."
  [session]
  (fn [form]
    (evaluation-wire (eval-session/evaluate! session {:form form}))))
