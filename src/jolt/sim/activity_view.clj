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
            [jolt.sim.presentation :as presentation]
            [jolt.sim.process-explorer :as process-explorer]))

(def page-type :jolt.sim.activity-view/page)
(def page-kind :jolt.sim.kind/activity-page)

(defn present-page
  "Projects one recovered activity page into a keyword-keyed immutable view.

  `page` is the closed value returned by process-explorer/read-activity-page.
  `registry` is an already trusted application/library activity presentation
  registry.  The result remains EDN data and preserves typed field values as
  well as their canonical EDN spelling; transport adapters decide which
  representation may cross their boundary."
  [page registry]
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
