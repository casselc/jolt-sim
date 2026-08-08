(ns jolt.sim.viewer-remote-session-consumer
  "Separate persistent outer Ripple pinned to one producer epoch."
  (:require [clojure.edn :as edn]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.viewer.remote-session :as remote-session]))

(def ^:private outer-token "ripple-remote-consumer-capability-0001")

(defn- await-release!
  [reconcile-path reconciled-path release-path reconcile-step!]
  (let [deadline (+ (System/nanoTime) (* 120000 1000000))]
    (loop [reconciled? false]
      (cond
        (.exists (java.io.File. release-path)) true

        (and (not reconciled?) (.exists (java.io.File. reconcile-path)))
        (let [{:keys [branch cursor]}
              (edn/read-string (slurp reconcile-path))
              result (reconcile-step! branch cursor)]
          (spit reconciled-path
                (str (trace/canonical-edn result) "\n"))
          (recur true))

        (< (System/nanoTime) deadline) (do (Thread/sleep 10)
                                           (recur reconciled?))
        :else (throw (ex-info "outer Ripple fixture release timed out"
                              {:type ::release-timeout
                               :release-path release-path}))))))

(defn -main
  [producer-ready-path outer-ready-path reconcile-path reconciled-path
   release-path]
  (let [read-only* (atom nil)
        steppable* (atom nil)]
    (try
      (let [source (edn/read-string (slurp producer-ready-path))
            source-coordinate
            (select-keys source
                         [:port :capability-token :session-instance-id])
            viewer-config
            {:port 0
             :capability-token outer-token
             :max-document-bytes (* 1024 1024)
             :allowed-scenarios #{'ripple.remote.fixture/unused}
             :runtime-config {:worker-command ["/bin/false"]
                              :dir "."
                              :timeout-ms 1000}}
            read-only
            (viewer/start-remote-session!
             viewer-config source-coordinate)
            steppable
            (viewer/start-remote-steppable-session!
             (assoc viewer-config :port 0) source-coordinate)
            attachment
            (remote-session/attachment source-coordinate (* 1024 1024))]
        (reset! read-only* read-only)
        (reset! steppable* steppable)
        (spit outer-ready-path
              (str (trace/canonical-edn
                    {:status :ready
                     :port (:port read-only)
                     :steppable-port (:port steppable)
                     :capability-token outer-token
                     :pinned-session-instance-id
                     (:session-instance-id source)})
                   "\n"))
        (await-release! reconcile-path reconciled-path release-path
                        (:reconcile-step! attachment)))
      (catch :default error
        (binding [*out* *err*]
          (println "remote Session consumer failed" (ex-message error)
                   (pr-str (ex-data error))))
        (System/exit 1))
      (finally
        (when-let [outer @steppable*]
          (try (viewer/stop! outer) (catch :default _ nil)))
        (when-let [outer @read-only*]
          (try (viewer/stop! outer) (catch :default _ nil)))))
    (System/exit 0)))
