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
    official-run->view-model / official-run->html
                       read-only retained official Maelstrom evidence, built
                       solely from its bounded public read-page projection.
    -main              ordinary-use entry point: jolt -M:trace-report
                       INPUT.edn [OUTPUT.html] or jolt -M:case-report
                       INPUT.edn [OUTPUT.html], or
                       jolt -M:official-run-report INPUT.edn [OUTPUT.html].
                       The default path reads exactly
                       one trace document via jolt.sim.trace/read-edn; a
                       leading --case-outcome selector (supplied by the
                       :case-report alias) reads exactly one Case/Outcome
                       document via jolt.sim.case-outcome/read-edn. Both paths
                       fail closed and refuse to overwrite the input file.

  The trace and Case/Outcome paths are deliberately separate: the two
  documents are distinct versioned contracts, and each renderer rejects the
  other's document shape rather than guessing a shared schema.

  Determinism: with the built-in presenters, or custom presenters that honor
  the documented deterministic/data-only contract, the view models contain
  only ordered data (event vectors, a
  sorted tag-count map, caller- or document-ordered monitor decisions, fixed
  known result sections followed by canonically ordered forward sections) and
  the rendered HTML embeds no wall-clock time,
  random ids, unordered map iteration, absolute host paths, environment data,
  or machine identity. Rendering the same document and deterministic options
  twice produces byte-identical HTML. Trusted custom presenter functions can
  violate that contract by consulting time, randomness, or mutable state; the
  renderer cannot prove function purity.

  Escaping: every value derived from a validated document and options is
  rendered inside an explicit Selmer escaping scope, so hostile strings stay
  inert even when some other library has changed Selmer's process-wide
  default."
  (:require [clojure.datafy :as datafy]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [jolt.fs :as fs]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.maelstrom.official-run :as official-run]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.report.outbox :as outbox]
            [jolt.sim.report-template :refer [embedded-html]]
            [jolt.sim.trace :as trace]
            [jolt.sim.trace-index :as trace-index]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(def view-model-version
  "Version of the trace view-model shape this namespace emits."
  2)

(def case-outcome-view-model-version
  "Version of the Case/Outcome view-model shape this namespace emits."
  2)

(def official-run-view-model-version
  "Version of the official Maelstrom run view-model shape."
  1)

(defn value->view-model
  "Returns the shared closed value/topology model for a trusted registry.

  Application semantics remain in the supplied immutable registry; this
  report namespace performs no application-specific dispatch or field search."
  [registry value]
  (presentation/present-value registry value))

(defn- escaped-html [value]
  (selmer-util/with-escaping
    (selmer/render "{{value}}" {:value (str value)})))

(defn- kind-text [value]
  (when value
    (str (namespace value) "/" (name value))))

(defn- value-field-html [{:keys [label value]}]
  (str "<dt>" (escaped-html label) "</dt><dd><code>"
       (escaped-html (trace/canonical-edn (trace/restore-value value)))
       "</code></dd>"))

(defn- value-fields-html [fields]
  (when (seq fields)
    (str "<dl class=\"jolt-sim-value-fields\">"
         (apply str (map value-field-html fields))
         "</dl>")))

(defn- value-topology-svg [graph]
  (when (and graph (seq (:nodes graph)))
    (let [nodes (:nodes graph)
          width (max 360 (* 190 (count nodes)))
          positions (into {}
                          (map-indexed
                           (fn [index node]
                             [(:id node) {:x (+ 95 (* index 190)) :y 95}])
                           nodes))]
      (str
       "<svg class=\"jolt-sim-value-topology\" viewBox=\"0 0 " width
       " 210\" role=\"img\" aria-label=\"Value topology\">"
       (apply str
              (map (fn [{:keys [id from to label status]}]
                     (let [{x1 :x y1 :y} (get positions from)
                           {x2 :x y2 :y} (get positions to)]
                       (str "<g data-edge-id=\"" (escaped-html id) "\">"
                            "<line x1=\"" x1 "\" y1=\"" y1
                            "\" x2=\"" x2 "\" y2=\"" y2
                            "\" stroke=\"currentColor\"/>"
                            "<text x=\"" (/ (+ x1 x2) 2) "\" y=\""
                            (- y1 12) "\" text-anchor=\"middle\">"
                            (escaped-html label) "</text><title>"
                            (escaped-html (or (kind-text status) ""))
                            "</title></g>")))
                   (:edges graph)))
       (apply str
              (map (fn [{:keys [id label status]}]
                     (let [{:keys [x y]} (get positions id)]
                       (str "<g data-node-id=\"" (escaped-html id) "\">"
                            "<rect x=\"" (- x 70) "\" y=\"" (- y 35)
                            "\" width=\"140\" height=\"70\" rx=\"8\""
                            " fill=\"Canvas\" stroke=\"currentColor\"/>"
                            "<text x=\"" x "\" y=\"" y
                            "\" text-anchor=\"middle\">"
                            (escaped-html label) "</text>"
                            "<text x=\"" x "\" y=\"" (+ y 20)
                            "\" text-anchor=\"middle\">"
                            (escaped-html (or (kind-text status) ""))
                            "</text></g>")))
                   nodes))
       "</svg>"))))

(defn- graph-details-html [graph]
  (when graph
    (str
     (apply str
            (map (fn [{:keys [id label fields]}]
                   (str "<details><summary>Node " (escaped-html label)
                        " <code>" (escaped-html id) "</code></summary>"
                        (value-fields-html fields) "</details>"))
                 (:nodes graph)))
     (apply str
            (map (fn [{:keys [id label fields]}]
                   (str "<details><summary>Edge " (escaped-html label)
                        " <code>" (escaped-html id) "</code></summary>"
                        (value-fields-html fields) "</details>"))
                 (:edges graph))))))

(defn value->html
  "Renders one value through the same bounded model consumed by Ripple.

  The result is a self-contained inert fragment: escaped SVG, bounded details,
  and canonical source EDN. It contains no script or application-specific
  renderer logic."
  [registry value]
  (let [{:keys [summary fields graph source-edn] :as model}
        (value->view-model registry value)]
    (str "<article class=\"jolt-sim-value-presentation\" data-kind=\""
         (escaped-html (kind-text (:kind model))) "\"><h1>"
         (escaped-html summary) "</h1>"
         (value-fields-html fields)
         (value-topology-svg graph)
         (graph-details-html graph)
         "<details><summary>Canonical source EDN</summary><pre>"
         (escaped-html source-edn)
         "</pre></details></article>")))

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
  #{:monitors :presentation-registry})

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
  "Builds a data-only view model for a trace document.

  `doc` must be an already formed versioned trace document; it is validated
  fail-closed through `jolt.sim.trace/validate-document!` before anything is
  read from it. `options` may carry `:monitors`, a vector of already-computed
  monitor decisions in the `jolt.sim.monitor/run-monitor` result shape
  `{:id .. :status .. :detail .. :index ..}`. Decisions are validated for their
  public shape and stable value domain only; no monitor function is ever
  invoked while rendering. `:presentation-registry` may supply an immutable
  registry of trusted presenter functions. It is composed after
  `jolt.sim.presentation/default-registry`, so application entries override
  built-ins while uploaded trace data remains incapable of loading code.
  Built-in presenters are deterministic. Custom presenters must honor the
  same contract for the resulting view model to remain reproducible.

  The returned map is ordered data: `:tag-counts` is a sorted map, `:events` is
  a vector of rows in trace order, and `:monitors` keeps the caller's order.
  Each decision retains its own fields and gains `:status-name`, `:id-edn`, and
  `:detail-edn` rendering projections. `:canonical-edn` is the byte-stable EDN
  of the complete validated document.

  Navigation: every event row gains a stable `:anchor`/`:href` and a `:nav`
  map of first/prev/next/last fragment targets computed by
  `jolt.sim.trace-index`. The view model also carries script-free
  `:tag-groups`, `:task-groups`, `:site-groups`, and `:time-groups` (each a
  vector of group rows linking back to matching events) plus a `:quick` map of
  terminal, failure, and monitor-flagged targets. Every target is a
  same-document fragment href so the report stays fully navigable under a
  no-script content security policy."
  ([doc]
   (trace->view-model doc nil))
  ([doc options]
   (trace/validate-document! doc)
   (validate-options! options)
   (let [events (:jolt.sim.trace/events doc)
         event-count (count events)
         monitors (validate-monitors! event-count
                                      (get options :monitors []))
         event-registry
         (presentation/registry presentation/default-registry
                                (get options :presentation-registry))
         monitor-rows
         (mapv (fn [decision]
                  ;; Preserve the public decision fields and add canonical text
                  ;; projections so nested maps never inherit host hash order in
                  ;; the rendered HTML.
                  (assoc decision
                         :status-name (name (:status decision))
                         :id-edn (trace/canonical-edn (:id decision))
                         :detail-edn (trace/canonical-edn (:detail decision))))
                monitors)
         base-rows (presentation/events->rows event-registry events)
         nav-positions (trace-index/positions event-count)
         rows (mapv (fn [row nav]
                      (assoc row
                             :anchor (:anchor nav)
                             :href (:href nav)
                             :nav nav))
                    base-rows nav-positions)
         tag-groups (trace-index/tag-groups events)
         task-groups (trace-index/task-groups events)
         site-groups (trace-index/site-groups events)
         time-groups (trace-index/time-groups events)
         terminal-targets (trace-index/terminal-targets events)
         failure-targets (trace-index/failure-targets events)
         monitor-targets
         (trace-index/monitor-targets (mapv :index monitor-rows))]
     (let [terminal (terminal-tag events)]
       {:view-model-version view-model-version
        :trace-version (:jolt.sim.trace/version doc)
        :event-count event-count
        :terminal-tag terminal
        :terminal-label (if terminal (keyword-text terminal) "unverified")
        :tag-counts (tag-counts events)
        :events rows
        :monitors monitor-rows
        :has-monitors (pos? (count monitor-rows))
        :canonical-edn (trace/canonical-edn doc)
        :nav-anchor trace-index/nav-anchor
        :nav-anchor-href trace-index/nav-anchor-href
        :nav-href trace-index/nav-anchor-href
        :has-nav (pos? event-count)
        :nav {:first {:index 0
                      :href (trace-index/event-anchor-href 0)}
              :last {:index (max 0 (dec event-count))
                     :href (trace-index/event-anchor-href
                            (max 0 (dec event-count)))}
              :has-multiple (> event-count 1)}
        :quick {:terminal terminal-targets
                :has-terminal (pos? (count terminal-targets))
                :failure failure-targets
                :has-failure (pos? (count failure-targets))
                :monitor-targets monitor-targets
                :has-monitor-targets (pos? (count monitor-targets))}
        :tag-groups tag-groups
        :has-tag-groups (pos? (count tag-groups))
        :task-groups task-groups
        :has-task-groups (pos? (count task-groups))
        :site-groups site-groups
        :has-site-groups (pos? (count site-groups))
        :time-groups time-groups
        :has-time-groups (pos? (count time-groups))
        :has-index (or (pos? (count tag-groups))
                       (pos? (count task-groups))
                       (pos? (count site-groups))
                       (pos? (count time-groups)))
        :tag-index-anchor trace-index/tag-index-anchor
        :task-index-anchor trace-index/task-index-anchor
        :site-index-anchor trace-index/site-index-anchor
        :time-index-anchor trace-index/time-index-anchor}))))

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
  ;; completed Case/Outcome value. This contains every section in the current
  ;; canonical outbox family. Absent sections are simply absent; forward or
  ;; application-specific keys follow these rows in canonical key order.
  [:terminal :boundary-log :clock :application :http :receiver :routes
   :sqlite :persistence :capacity :fault :admission :schedule :cleanup
   :clean?])

(def ^:private result-section-key-set
  (set result-section-keys))

(defn- result-section-row [known? k value]
  {:key-edn (trace/canonical-edn k)
   :known? known?
   :section-kind-name (if known? "known" "forward")
   :edn (trace/canonical-edn value)})

(defn- result-sections
  "Projects the known whole-application sections present in an ordinary
  completed result. Known rows use fixed `result-section-keys` order, followed
  by every unknown/forward row in canonical key order. Each row carries
  byte-stable key and value EDN. Absent sections are absent while a present nil
  section remains visible."
  [result]
  (if (map? result)
    (let [known
          (into []
                (keep (fn [k]
                        (when (contains? result k)
                          (result-section-row true k (get result k)))))
                result-section-keys)
          forward-keys
          (->> (keys result)
               (remove result-section-key-set)
               ;; Match jolt.sim.trace's canonical map-key ordering rather
               ;; than ordinary host comparison or insertion order.
               (sort-by (comp pr-str trace/canonical-value)))]
      (into known
            (map (fn [k]
                   (result-section-row false k (get result k))))
            forward-keys))
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
  [scenario outcome]
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
              :has-sections false
              :outbox-journey nil
              :has-outbox-journey false}]
    (case status
      :completed
      (let [result (:result outcome)
            sections (result-sections result)
            journey (outbox/project scenario result)]
        (assoc base
               :has-value true
               :value-edn (trace/canonical-edn result)
               :sections sections
               :has-sections (pos? (count sections))
               :outbox-journey journey
               :has-outbox-journey (some? journey)))

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
  that fixed order when present; absent sections are simply absent, and every
  unknown/forward section follows in deterministic canonical key order. The
  exact ordinary Case is also rendered as the canonical replay coordinate.
  Completed results from the four canonical outbox scenarios gain a pure,
  evidence-only journey projection; missing optional evidence stays missing.
  The ordered
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
      :replay-coordinate ordinary-case
      :replay-coordinate-edn (trace/canonical-edn ordinary-case)
      :schedule schedule
      :schedule-edn (trace/canonical-edn schedule)
      :has-schedule (some? schedule)
      :monitors monitor-rows
      :has-monitors (pos? (count monitor-rows))
      :monitor-count (count monitor-rows)
      :canonical-edn (case-outcome/canonical-edn doc)}
     (outcome-view-model (:scenario ordinary-case) ordinary-outcome))))

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

(defn- official-run-pages
  "Reads the complete bounded capture through the validate-once public source."
  [doc]
  (let [source (official-run/source doc)]
    (loop [page (datafy/datafy source) header nil operations []]
      (let [cursor (:cursor page)
          next-cursor (:next-cursor page)
          operations (into operations (:operations page))]
        (when (and (:remaining? page) (<= next-cursor cursor))
          (throw (ex-info "Official-run paging made no progress"
                          {:type official-run/invalid-cursor :cursor cursor})))
        (if (:remaining? page)
          (recur (datafy/nav source :next-page (:next-page page))
                 (or header (:header page))
                 operations)
          {:page page
           :header (or header (:header page))
           :operations operations})))))

(defn official-run->view-model
  "Builds a deterministic static-report model exclusively from read-page."
  [doc]
  (let [{:keys [page header operations]} (official-run-pages doc)
        {:keys [run outcome history artifacts]} header]
    {:view-model-version official-run-view-model-version
     :document-version (:version page)
     :profile-name (str (:profile run))
     :workload-name (str (:workload run))
     :parameters-edn (trace/canonical-edn (:parameters run))
     :status-name (name (:status outcome))
     :exit-edn (trace/canonical-edn (:exit outcome))
     :official-valid-edn (trace/canonical-edn (:official-valid? outcome))
     :workload-valid-edn (trace/canonical-edn (:workload-valid? outcome))
     :checks-edn (trace/canonical-edn (:checks outcome))
     :stats-edn (trace/canonical-edn (:stats outcome))
     :total-count (:total-count history)
     :captured-count (:captured-count history)
     :truncated? (:truncated? history)
     :history-artifact (:artifact history)
     :artifacts (mapv #(assoc % :role-text (str (:role %))) artifacts)
     :artifact-count (count artifacts)
     :operations
     (mapv (fn [operation]
             (assoc operation
                    :type-name (name (:type operation))
                    :function-name (str (:f operation))
                    :process-edn (trace/canonical-edn (:process operation))
                    :value-edn (trace/canonical-edn (:value operation))
                    :raw-edn (trace/canonical-edn (:raw operation))))
           operations)}))

(def ^:private official-run-template
  (embedded-template "jolt/sim/official_maelstrom_run.html"))

(defn official-run->html
  "Validates and renders one retained official Maelstrom run read-only."
  [doc]
  (strip-render-line-end-whitespace
   (selmer-util/with-escaping
     (selmer/render official-run-template (official-run->view-model doc)))))

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

(def official-run-selector "--official-maelstrom-run")

(defn- parse-arguments
  "Splits `args` into an explicit report selector and the remaining
  input/output arguments. Explicit selectors choose Case/Outcome or retained
  official Maelstrom evidence; any other leading `--` option is rejected
  rather than being guessed at. Without a selector the default trace path is
  used, preserving `jolt -M:trace-report` behavior."
  [args]
  (let [selector (first args)]
    (if (and (string? selector)
             (string/starts-with? selector "--"))
      (do
        (when-not (contains? #{case-outcome-selector official-run-selector}
                             selector)
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
         jolt -M:official-run-report INPUT.edn [OUTPUT.html]

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
        official-run? (= official-run-selector selector)
        usage (cond
                case-outcome? "Usage: jolt -M:case-report INPUT.edn [OUTPUT.html]"
                official-run? "Usage: jolt -M:official-run-report INPUT.edn [OUTPUT.html]"
                :else "Usage: jolt -M:trace-report INPUT.edn [OUTPUT.html]")
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
    (let [read-doc (cond case-outcome? case-outcome/read-edn
                         official-run? official-run/read-edn
                         :else trace/read-edn)
          render (cond case-outcome? case-outcome->html
                       official-run? official-run->html
                       :else trace->html)
          doc (read-doc (slurp input))
          html (render doc)]
      (spit output html)
      (println (str "wrote " output))
      (flush))))
