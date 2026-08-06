(ns jolt.sim.activity-view
  "UI-neutral inspection values for retained semantic activity.

  This namespace is the shared boundary between retained process outcomes and
  consumers such as a REPL, tap viewer, static report, Ripple, or a future
  Glimmer/TUI client.  It owns no HTTP, JSON, DOM, renderer, or artifact-path
  policy: trusted I/O remains in process-explorer and semantic projection
  remains in presentation.

  `read-page` is the direct immutable-data API.  `source` adds standard
  Clojure datafy/nav ergonomics over the same values; navigation is explicit
  and cursor-token based, and never mutates the source or simulation."
  (:require [clojure.core.protocols :as protocols]
            [jolt.sim.activity :as activity]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.process-explorer :as process-explorer]
            [jolt.sim.trace :as trace]))

(def page-type :jolt.sim.activity-view/page)
(def page-kind :jolt.sim.kind/activity-page)

(def ^:private page-keys
  #{:version :cursor :next-cursor :accepted-count :remaining? :events
    :recovery :observer-status})

(def ^:private recovery-keys
  #{:status :reason :sequence :last-good-offset :raw-tail-bytes
    :image-truncated? :class})

(defn- invalid-page! [reason]
  (throw
   (ex-info "Retained activity page is malformed"
            {:type :jolt.sim.activity-view/invalid-page
             :reason reason})))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- validate-recovery! [recovery]
  (when-not (and (map? recovery)
                 (= recovery-keys (set (keys recovery))))
    (invalid-page! :invalid-recovery-shape))
  (when-not (contains? #{:complete :partial :failed} (:status recovery))
    (invalid-page! :invalid-recovery-status))
  (when-not (or (nil? (:reason recovery))
                (keyword? (:reason recovery)))
    (invalid-page! :invalid-recovery-reason))
  (when-not (and (non-negative-integer? (:sequence recovery))
                 (<= (:sequence recovery) activity/max-records)
                 (non-negative-integer? (:last-good-offset recovery))
                 (non-negative-integer? (:raw-tail-bytes recovery))
                 (boolean? (:image-truncated? recovery))
                 (or (nil? (:class recovery))
                     (string? (:class recovery))))
    (invalid-page! :invalid-recovery-value))
  recovery)

(defn- validate-page! [page]
  (when-not (and (map? page) (= page-keys (set (keys page))))
    (invalid-page! :invalid-page-shape))
  (when-not (= 1 (:version page))
    (invalid-page! :unsupported-version))
  (let [{:keys [cursor next-cursor accepted-count remaining? events]} page]
    (when-not (and (non-negative-integer? cursor)
                   (non-negative-integer? next-cursor)
                   (non-negative-integer? accepted-count)
                   (<= cursor next-cursor accepted-count activity/max-records))
      (invalid-page! :invalid-cursor-range))
    (when-not (and (boolean? remaining?)
                   (= remaining? (< next-cursor accepted-count)))
      (invalid-page! :invalid-continuation))
    (when-not (and (vector? events)
                   (<= (count events) activity/max-page-events)
                   (= (- next-cursor cursor) (count events))
                   (= (vec (range cursor next-cursor))
                      (mapv :sequence events))
                   (every? #(and (map? %)
                                 (= #{:sequence :event} (set (keys %))))
                           events))
      (invalid-page! :invalid-events))
    (let [payload-sizes
          (try
            (mapv (fn [{:keys [event]}]
                    (alength (.getBytes (trace/canonical-edn event) "UTF-8")))
                  events)
            (catch :default _
              (invalid-page! :invalid-event-value)))]
      (when-not (and (every? #(<= % activity/max-payload-bytes)
                             payload-sizes)
                     (<= (reduce + 0 payload-sizes)
                         activity/max-page-payload-bytes))
        (invalid-page! :invalid-event-size))))
  (validate-recovery! (:recovery page))
  (when-not (= (:accepted-count page)
               (get-in page [:recovery :sequence]))
    (invalid-page! :invalid-recovery-sequence))
  (let [status (:observer-status page)]
    (when (and (some? status)
               (not (activity/valid-observer-status? status)))
      (invalid-page! :invalid-observer-status)))
  page)

(defn present-page
  "Projects one recovered activity page into a keyword-keyed immutable view.

  `page` is the closed value returned by process-explorer/read-activity-page.
  `registry` is an already trusted application/library activity presentation
  registry.  The result remains EDN data and preserves typed field values as
  well as their canonical EDN spelling; transport adapters decide which
  representation may cross their boundary."
  [page registry]
  (validate-page! page)
  (let [present (presentation/activity-event-presenter registry)
        events (mapv (fn [{:keys [sequence event]}]
                       (assoc (present sequence event)
                              :sequence sequence))
                     (:events page))
        next-cursor (:next-cursor page)]
    {:jolt.sim.activity-view/type page-type
     :kind page-kind
     :version 1
     :status :ok
     :cursor (:cursor page)
     :next-cursor next-cursor
     :next-page (when (:remaining? page) {:cursor next-cursor})
     :accepted-count (:accepted-count page)
     :remaining? (boolean (:remaining? page))
     :events events
     :recovery (:recovery page)
     :observer-status (:observer-status page)}))

(defn read-page
  "Reads and presents one retained outcome page as UI-neutral EDN data."
  ([outcome registry]
   (read-page outcome 0 registry))
  ([outcome cursor registry]
   (present-page
    (process-explorer/read-activity-page outcome cursor)
    registry)))

(defn- navigation-error [value]
  (ex-info "Activity page navigation token is malformed"
           {:type :jolt.sim.activity-view/invalid-navigation
            :value value}))

(defn source
  "Returns an immutable datafy/nav source for one retained activity outcome.

  `(clojure.datafy/datafy source)` reads page zero.  Navigating its
  `:next-page` value reads the indicated continuation page from the same
  trusted outcome and registry.  The datafied values are the exact values
  returned by `read-page`, so callers may also pass them directly to `tap>` or
  their own renderer without a Ripple dependency."
  [outcome registry]
  (letfn [(load-page [cursor]
            (read-page outcome cursor registry))]
    (reify
      protocols/Datafiable
      (datafy [_]
        (load-page 0))

      protocols/Navigable
      (nav [this key value]
        (if (= :next-page key)
          (if (nil? value)
            nil
            (do
              (when-not (and (map? value)
                             (= #{:cursor} (set (keys value)))
                             (integer? (:cursor value))
                             (not (neg? (:cursor value))))
                (throw (navigation-error value)))
              (with-meta (load-page (:cursor value))
                {:clojure.datafy/obj this})))
          value)))))
