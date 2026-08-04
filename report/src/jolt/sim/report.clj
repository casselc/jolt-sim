(ns jolt.sim.report
  "Static, self-contained HTML reports for validated jolt-sim documents.

  This is the first shared view-model/rendering slice for a later live web or
  GTK viewer: it consumes the existing trace document, Case/Outcome document,
  and monitor-result contracts unchanged and creates no new trace schema,
  simulator, server, or controller.

  API:
    trace->view-model  pure public trace-to-view-model function. Validates the
                       trace document fail-closed through
                       jolt.sim.trace/validate-document!, optionally accepts
                       already-computed monitor decisions as data, and never
                       runs a monitor function.
    trace->html        validate + view model + render in one call.
    case-outcome->view-model
                       pure public Case/Outcome-to-view-model function.
                       Validates the document fail-closed through
                       jolt.sim.case-outcome/validate-document! and projects
                       the case, the discriminated outcome, the known
                       whole-application result sections, and the ordered
                       monitor decisions to data.
    case-outcome->html validate + view model + render in one call.
    -main              ordinary-use entry point: jolt -M:trace-report
                       INPUT.edn [OUTPUT.html] or jolt -M:case-report
                       INPUT.edn [OUTPUT.html]. The default path reads exactly
                       one trace document via jolt.sim.trace/read-edn; a
                       leading --case-outcome selector (supplied by the
                       :case-report alias) reads exactly one Case/Outcome
                       document via jolt.sim.case-outcome/read-edn. Both paths
                       fail closed and refuse to overwrite the input file.

  The trace and Case/Outcome paths are deliberately separate: the two
  documents are distinct versioned contracts, and each renderer rejects the
  other's document shape rather than guessing a shared schema.

  Determinism: the view models contain only ordered data (event vectors, a
  sorted tag-count map, caller- or document-ordered monitor decisions, a fixed
  result-section order) and the rendered HTML embeds no wall-clock time,
  random ids, unordered map iteration, absolute host paths, environment data,
  or machine identity. Rendering the same document and options twice produces
  byte-identical HTML.

  Escaping: every value derived from a validated document and options is
  rendered inside an explicit Selmer escaping scope, so hostile strings stay
  inert even when some other library has changed Selmer's process-wide
  default."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [jolt.fs :as fs]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.report-template :refer [embedded-html]]
            [jolt.sim.trace :as trace]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(def view-model-version
  "Version of the trace view-model shape this namespace emits."
  1)

(def case-outcome-view-model-version
  "Version of the Case/Outcome view-model shape this namespace emits."
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

(defn- strip-render-line-end-whitespace
  "Removes indentation left behind when Selmer erases control-tag lines.
  Generated reports remain readable, deterministic, and diff-check clean."
  [html]
  (-> html
      (string/replace #"[ \t]+\n" "\n")
      (string/replace #"[ \t]+\z" "")))

(defn- render-report
  "Internal renderer for the validated view model produced by
  `trace->view-model`. Keeping this private prevents callers from supplying
  Selmer's deliberate `[:safe ...]` escape-bypass sentinel as arbitrary view
  data."
  [view-model]
  (strip-render-line-end-whitespace
   (selmer-util/with-escaping
     (selmer/render (template-source) view-model))))

(defn trace->html
  "Validates `doc`, builds its view model, and renders the self-contained HTML
  report. `options` is passed through to `trace->view-model`."
  ([doc]
   (trace->html doc nil))
  ([doc options]
   (render-report (trace->view-model doc options))))

;; Case/Outcome reports. This path consumes the versioned jolt.sim.case-outcome
;; v1 contract only; cooperative trace documents are rejected here, and
;; Case/Outcome documents are rejected by the trace renderer above.

(def ^:private result-section-keys
  ;; Fixed display order for the known whole-application result sections of a
  ;; completed Case/Outcome value. Absent sections are simply absent from the
  ;; view model; unknown keys remain visible only in the value and document
  ;; EDN projections.
  [:application :http :receiver :routes :sqlite :capacity :fault :admission
   :schedule :clean?])

(defn- result-sections
  "Projects the known whole-application sections present in an ordinary
  completed result, in the fixed `result-section-keys` order. Each row is
  {:name .. :edn ..} with byte-stable ordinary-value EDN; absent sections are
  simply absent while a present nil section is still shown."
  [result]
  (if (map? result)
    (into []
          (keep (fn [k]
                  (when (contains? result k)
                    {:name (name k)
                     :edn (trace/canonical-edn (get result k))})))
          result-section-keys)
    []))

(defn- monitor-decision-row
  "Projects one restored Case/Outcome monitor decision to its rendering row.
  The ordinary :id and :detail become byte-stable EDN text; the raw :status
  keyword and :index are retained. Caller order is preserved by the enclosing
  mapv."
  [decision]
  {:id-edn (trace/canonical-edn (:id decision))
   :status (:status decision)
   :status-name (name (:status decision))
   :detail-edn (trace/canonical-edn (:detail decision))
   :index (:index decision)})

(defn- outcome-view-model
  "Projects a validated ordinary outcome map. Every status renders its
  discriminated :status and :exit; :completed adds canonical :value EDN and
  the known result sections, :failed and :worker-error add canonical :error
  EDN, and :timeout adds its :reason. Flags and EDN text are explicit for
  absent fields so the template never guesses."
  [outcome]
  (let [status (:status outcome)
        exit (:exit outcome)
        base {:outcome-status status
              :outcome-status-name (name status)
              :outcome-exit exit
              :exit-edn (pr-str exit)
              :has-reason false
              :reason nil
              :reason-name nil
              :has-value false
              :value-edn nil
              :has-error false
              :error-edn nil
              :sections []
              :has-sections false}]
    (case status
      :completed
      (let [sections (result-sections (:result outcome))]
        (assoc base
               :has-value true
               :value-edn (trace/canonical-edn (:result outcome))
               :sections sections
               :has-sections (pos? (count sections))))

      :failed
      (assoc base
             :has-error true
             :error-edn (trace/canonical-edn (:error outcome)))

      :timeout
      (assoc base
             :has-reason true
             :reason (:reason outcome)
             :reason-name (name (:reason outcome)))

      :worker-error
      (assoc base
             :has-error true
             :error-edn (trace/canonical-edn (:error outcome))))))

(defn case-outcome->view-model
  "Builds a deterministic, data-only view model for a Case/Outcome document.

  `doc` must be an already formed versioned Case/Outcome document; it is
  validated fail-closed through `jolt.sim.case-outcome/validate-document!`
  before anything is read from it. This path is deliberately separate from
  `trace->view-model`: the two documents are distinct versioned contracts and
  each renderer rejects the other's shape.

  The returned map is ordered, host-independent data. The Case/Outcome codec's
  public restoration helpers provide the ordinary case, outcome, and monitor
  values; this renderer does not inspect their private stored tags. The case
  projection carries the scenario symbol's name, the mode, byte-stable EDN of
  the ordinary input, and the exact future schedule (`nil` when the case ran
  unscheduled). The outcome projection carries the discriminated status, the
  exit, and -- as applicable -- byte-stable ordinary-value EDN of a `:completed`
  result, error EDN of a `:failed`/`:worker-error` outcome, or
  the `:deadline` reason of a `:timeout`. For a completed whole-application
  result, the known sections named by `result-section-keys` are projected in
  that fixed order when present; absent sections are simply absent, and
  unknown keys remain visible only in the value and document EDN. The ordered
  monitor decisions keep document order and gain `:status-name`, `:id-edn`,
  and `:detail-edn` rendering projections. `:canonical-edn` is the
  byte-stable EDN of the complete validated document."
  [doc]
  (case-outcome/validate-document! doc)
  (let [ordinary-case (case-outcome/restore-case doc)
        ordinary-outcome (case-outcome/restore-outcome doc)
        schedule (:schedule ordinary-case)
        monitor-rows (mapv monitor-decision-row
                           (case-outcome/restore-monitors doc))]
    (merge
     {:view-model-version case-outcome-view-model-version
      :document-version (:jolt.sim.case-outcome/version doc)
      :scenario-name (str (:scenario ordinary-case))
      :mode (:mode ordinary-case)
      :mode-name (name (:mode ordinary-case))
      :input-edn (trace/canonical-edn (:input ordinary-case))
      :schedule schedule
      :schedule-edn (trace/canonical-edn schedule)
      :has-schedule (some? schedule)
      :monitors monitor-rows
      :has-monitors (pos? (count monitor-rows))
      :monitor-count (count monitor-rows)
      :canonical-edn (case-outcome/canonical-edn doc)}
     (outcome-view-model ordinary-outcome))))

(defmacro ^:private embedded-template
  "Expands to the complete contents of the named template resource as a
  string literal, failing closed when the resource is missing during
  analysis. As with jolt.sim.report-template/embedded-html, reading during
  analysis keeps a standalone image free of runtime resource or
  dependency-source lookups."
  [resource]
  (let [found (io/resource resource)]
    (when-not found
      (throw
       (ex-info
        "Case/Outcome report template is missing during analysis"
        {:type ::missing-template
         :resource resource})))
    (slurp found)))

(def ^:private case-outcome-template
  ;; The Case/Outcome template resource is read while this namespace is
  ;; analyzed and compiled into this var, mirroring `report-template`.
  (embedded-template "jolt/sim/case_outcome.html"))

(defn- render-case-outcome-report
  "Internal renderer for the validated view model produced by
  `case-outcome->view-model`. Keeping this private prevents callers from
  supplying Selmer's deliberate `[:safe ...]` escape-bypass sentinel as
  arbitrary view data."
  [view-model]
  (strip-render-line-end-whitespace
   (selmer-util/with-escaping
     (selmer/render case-outcome-template view-model))))

(defn case-outcome->html
  "Validates `doc` as a Case/Outcome document, builds its view model, and
  renders the deterministic, self-contained HTML case report."
  [doc]
  (render-case-outcome-report (case-outcome->view-model doc)))

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
      "Refusing to overwrite the input file"
      {:type invalid-arguments
       :reason :input-output-alias
       :input input
       :output output}))))

(def case-outcome-selector
  "Explicit `-main` selector that routes to the Case/Outcome renderer. The
  `:case-report` alias passes this as its first main option so the CLI never
  guesses which schema an input file uses."
  "--case-outcome")

(defn- parse-arguments
  "Splits `args` into an explicit report selector and the remaining
  input/output arguments. A leading `--case-outcome` selects the Case/Outcome
  renderer; any other leading `--` option is rejected as an unknown selector
  rather than being guessed at. Without a selector the default trace path is
  used, preserving `jolt -M:trace-report` behavior."
  [args]
  (let [selector (first args)]
    (if (and (string? selector)
             (string/starts-with? selector "--"))
      (do
        (when-not (= case-outcome-selector selector)
          (throw
           (ex-info
            "Unknown report selector"
            {:type invalid-arguments
             :reason :unknown-selector
             :selector selector})))
        [selector (rest args)])
      [nil args])))

(defn -main
  "Writes a static HTML report for one EDN file.

  Usage: jolt -M:trace-report INPUT.edn [OUTPUT.html]
         jolt -M:case-report INPUT.edn [OUTPUT.html]

  The default path reads exactly one trace document through
  `jolt.sim.trace/read-edn` (which validates the complete document
  fail-closed), renders it, and writes the report. A leading `--case-outcome`
  selector (supplied by the `:case-report` alias) reads exactly one
  Case/Outcome document through `jolt.sim.case-outcome/read-edn` and renders
  it through `case-outcome->html`; the two schemas are never guessed at.
  Without OUTPUT.html the output path is INPUT with the .edn suffix replaced
  by .html. Refuses to write when the resolved output path aliases the input
  file, and never touches the output when the input is invalid. Prints the
  final output path on success."
  [& args]
  (let [[selector args] (parse-arguments args)
        case-outcome? (= case-outcome-selector selector)
        usage (if case-outcome?
                "Usage: jolt -M:case-report INPUT.edn [OUTPUT.html]"
                "Usage: jolt -M:trace-report INPUT.edn [OUTPUT.html]")
        [input output]
        (case (count args)
          1 [(first args) (default-output-path (first args))]
          2 [(first args) (second args)]
          (throw
           (ex-info
            usage
            {:type invalid-arguments
             :reason :wrong-argument-count
             :args (vec args)})))]
    (ensure-distinct-paths! input output)
    (let [read-doc (if case-outcome? case-outcome/read-edn trace/read-edn)
          render (if case-outcome? case-outcome->html trace->html)
          doc (read-doc (slurp input))
          html (render doc)]
      (spit output html)
      (println (str "wrote " output))
      (flush))))
