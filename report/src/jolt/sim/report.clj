(ns jolt.sim.report
  "Static, self-contained HTML reports for validated jolt-sim trace documents.

  This is the first shared view-model/rendering slice for a later live web or
  GTK viewer: it consumes the existing trace document and monitor-result
  contracts unchanged and creates no new trace schema, simulator, server, or
  controller.

  API:
    trace->view-model  pure public trace-to-view-model function. Validates the
                       trace document fail-closed through
                       jolt.sim.trace/validate-document!, optionally accepts
                       already-computed monitor decisions as data, and never
                       runs a monitor function.
    trace->html        validate + view model + render in one call.
    -main              ordinary-use entry point: jolt -M:trace-report
                       INPUT.edn [OUTPUT.html]. Reads exactly one trace
                       document via jolt.sim.trace/read-edn, fails closed, and
                       refuses to overwrite the input file.

  Determinism: the view model contains only ordered data (event vectors, a
  sorted tag-count map, caller-ordered monitor decisions) and the rendered HTML
  embeds no wall-clock time, random ids, unordered map iteration, absolute host
  paths, environment data, or machine identity. Rendering the same document and
  options twice produces byte-identical HTML.

  Escaping: every value derived from the validated trace and options is
  rendered inside an explicit Selmer escaping scope, so hostile strings stay
  inert even when some other library has changed Selmer's process-wide
  default."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [jolt.fs :as fs]
            [jolt.sim.report-template :refer [embedded-html]]
            [jolt.sim.trace :as trace]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(def view-model-version
  "Version of the view-model shape this namespace emits."
  1)

(def invalid-monitor-result
  "Type value for a malformed supplied monitor decision."
  ::invalid-monitor-result)

(def invalid-options
  "Type value for malformed report options."
  ::invalid-options)

(def invalid-arguments
  "Type value for malformed -main arguments or an input/output alias."
  ::invalid-arguments)

(def ^:private terminal-tags
  #{:run/completed :run/failed :run/deadlock :run/step-limit})

;; The stable value domain of a monitor decision, mirroring the public contract
;; of jolt.sim.monitor/run-monitor: {:id .. :status .. :detail .. :index ..}
;; with :status one of these three keywords. Kept here as data so the report
;; validates supplied decisions without invoking any monitor function.
(def ^:private decision-statuses
  #{:pass :violation :inconclusive})

(def ^:private decision-keys
  #{:id :status :detail :index})

(def ^:private option-keys
  #{:monitors})

(defn- keyword-text [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- terminal-tag [events]
  ;; Structural trace validation deliberately does not claim the temporal
  ;; grammar. Report an outcome only when exactly one terminal exists and it is
  ;; the final event; otherwise the report says that the outcome is unverified.
  (let [terminals (filterv #(contains? terminal-tags (first %)) events)]
    (when (and (= 1 (count terminals))
               (= (first terminals) (peek events)))
      (first (first terminals)))))

(defn- tag-counts [events]
  ;; sorted-map keeps the tag histogram byte-stable regardless of hash order.
  (into (sorted-map) (frequencies (map first events))))

(defn- event-step [tag event]
  (when-not (= :run/initial tag)
    (nth event 1)))

(defn- event-time [tag event]
  (case tag
    :time/advance (str (nth event 2) " -> " (nth event 3))
    :run/initial nil
    (nth event 2)))

(defn- event-task [tag event]
  (case tag
    :schedule/choose (nth event 4)
    (:task/transition :run/failed) (nth event 3)
    nil))

(defn- event-edn
  "Byte-stable EDN of one complete event."
  [event]
  (trace/canonical-edn event))

(defn- event-row [index event]
  (let [tag (first event)]
    {:index index
     :tag (keyword-text tag)
     :step (event-step tag event)
     :time (event-time tag event)
     :task (event-task tag event)
     :edn (event-edn event)}))

(defn- validate-monitor-result! [event-count decision]
  (when-not (map? decision)
    (throw
     (ex-info
      "Monitor decision is not a map"
      {:type invalid-monitor-result
       :reason :not-a-map
       :detail (str (class decision))})))
  (when-not (= decision-keys (set (keys decision)))
    (throw
     (ex-info
      "Monitor decision has unexpected keys"
      {:type invalid-monitor-result
       :reason :wrong-keys
       :detail (set (keys decision))})))
  (when-not (contains? decision-statuses (:status decision))
    (throw
     (ex-info
      "Monitor decision has an unsupported status"
      {:type invalid-monitor-result
       :reason :bad-status
       :detail (:status decision)})))
  (let [index (:index decision)]
    (when-not (or (nil? index)
                  (and (integer? index)
                       (not (neg? index))
                       (< index event-count)))
      (throw
       (ex-info
        "Monitor decision has an invalid index"
        {:type invalid-monitor-result
         :reason (if (and (integer? index) (not (neg? index)))
                   :index-out-of-range
                   :bad-index)
         :detail index}))))
  ;; Reject arbitrary objects/functions by requiring the stable value domain.
  (trace/canonical-value (:id decision) [:monitor :id])
  (when (some? (:detail decision))
    (trace/canonical-value (:detail decision) [:monitor :detail]))
  decision)

(defn- validate-monitors! [event-count monitors]
  (when-not (vector? monitors)
    (throw
     (ex-info
      "Monitor results must be a vector"
      {:type invalid-monitor-result
       :reason :not-a-vector
       :detail (str (class monitors))})))
  (mapv #(validate-monitor-result! event-count %) monitors))

(defn- validate-options! [options]
  (when-not (or (nil? options) (map? options))
    (throw
     (ex-info
      "Report options must be a map"
      {:type invalid-options
       :reason :not-a-map
       :detail (str (class options))})))
  (when-not (every? option-keys (keys options))
    (throw
     (ex-info
      "Report options contain unsupported keys"
      {:type invalid-options
       :reason :wrong-keys
       :detail (set (keys options))})))
  options)

(defn trace->view-model
  "Builds a deterministic, data-only view model for a trace document.

  `doc` must be an already formed versioned trace document; it is validated
  fail-closed through `jolt.sim.trace/validate-document!` before anything is
  read from it. `options` may carry `:monitors`, a vector of already-computed
  monitor decisions in the `jolt.sim.monitor/run-monitor` result shape
  `{:id .. :status .. :detail .. :index ..}`. Decisions are validated for their
  public shape and stable value domain only; no monitor function is ever
  invoked while rendering.

  The returned map is ordered data: `:tag-counts` is a sorted map, `:events` is
  a vector of rows in trace order, and `:monitors` keeps the caller's order.
  Each decision retains its own fields and gains `:status-name`, `:id-edn`, and
  `:detail-edn` rendering projections. `:canonical-edn` is the byte-stable EDN
  of the complete validated document."
  ([doc]
   (trace->view-model doc nil))
  ([doc options]
   (trace/validate-document! doc)
   (validate-options! options)
   (let [events (:jolt.sim.trace/events doc)
         monitors (validate-monitors! (count events)
                                      (get options :monitors []))
         monitor-rows
         (mapv (fn [decision]
                 ;; Preserve the public decision fields and add canonical text
                 ;; projections so nested maps never inherit host hash order in
                 ;; the rendered HTML.
                 (assoc decision
                        :status-name (name (:status decision))
                        :id-edn (trace/canonical-edn (:id decision))
                        :detail-edn (trace/canonical-edn (:detail decision))))
               monitors)]
     (let [terminal (terminal-tag events)]
       {:view-model-version view-model-version
        :trace-version (:jolt.sim.trace/version doc)
        :event-count (count events)
        :terminal-tag terminal
        :terminal-label (if terminal (keyword-text terminal) "unverified")
        :tag-counts (tag-counts events)
        :events (mapv event-row (range) events)
        :monitors monitor-rows
        :has-monitors (pos? (count monitor-rows))
        :canonical-edn (trace/canonical-edn doc)}))))

(def ^:private report-template
  ;; `embedded-html` reads the maintainable HTML resource while this namespace
  ;; is analyzed and expands to the complete string literal. A downstream
  ;; standalone image therefore needs neither jolt-sim's source checkout nor
  ;; dependency resource embedding at runtime.
  (embedded-html))

(defn- template-source []
  report-template)

(defn- render-report
  "Internal renderer for the validated view model produced by
  `trace->view-model`. Keeping this private prevents callers from supplying
  Selmer's deliberate `[:safe ...]` escape-bypass sentinel as arbitrary view
  data."
  [view-model]
  (selmer-util/with-escaping
    (selmer/render (template-source) view-model)))

(defn trace->html
  "Validates `doc`, builds its view model, and renders the self-contained HTML
  report. `options` is passed through to `trace->view-model`."
  ([doc]
   (trace->html doc nil))
  ([doc options]
   (render-report (trace->view-model doc options))))

(defn- absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn- default-output-path [input]
  (if (string/ends-with? input ".edn")
    (str (subs input 0 (- (count input) 4)) ".html")
    (str input ".html")))

(defn- ensure-distinct-paths! [input output]
  (when (or (= (absolute-path input) (absolute-path output))
            ;; Absolute string equality cannot detect `.`/`..`, case-folded
            ;; Windows names, hard links, or symbolic links. Once both names
            ;; exist, ask the host filesystem whether they identify one file.
            (and (fs/exists? input)
                 (fs/exists? output)
                 (fs/same-file? input output)))
    (throw
     (ex-info
      "Refusing to overwrite the trace input file"
      {:type invalid-arguments
       :reason :input-output-alias
       :input input
       :output output}))))

(defn -main
  "Writes a static HTML report for one trace EDN file.

  Usage: jolt -M:trace-report INPUT.edn [OUTPUT.html]

  Reads exactly one trace document through `jolt.sim.trace/read-edn` (which
  validates the complete document fail-closed), renders it, and writes the
  report. Without OUTPUT.html the output path is INPUT with the .edn suffix
  replaced by .html. Refuses to write when the resolved output path aliases the
  input file, and never touches the output when the input is invalid. Prints
  the final output path on success."
  [& args]
  (let [[input output]
        (case (count args)
          1 [(first args) (default-output-path (first args))]
          2 [(first args) (second args)]
          (throw
           (ex-info
            "Usage: jolt -M:trace-report INPUT.edn [OUTPUT.html]"
            {:type invalid-arguments
             :reason :wrong-argument-count
             :args (vec args)})))]
    (ensure-distinct-paths! input output)
    (let [doc (trace/read-edn (slurp input))
          html (trace->html doc)]
      (spit output html)
      (println (str "wrote " output))
      (flush))))
