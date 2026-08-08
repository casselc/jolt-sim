(ns jolt.sim.viewer-remote-session-consumer
  "Separate persistent outer Ripple pinned to one producer epoch."
  (:require [clojure.edn :as edn]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]))

(def ^:private outer-token "ripple-remote-consumer-capability-0001")

(defn- await-release! [release-path]
  (let [deadline (+ (System/nanoTime) (* 120000 1000000))]
    (loop []
      (cond
        (.exists (java.io.File. release-path)) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
        :else (throw (ex-info "outer Ripple fixture release timed out"
                              {:type ::release-timeout
                               :release-path release-path}))))))

(defn -main [producer-ready-path outer-ready-path release-path]
  (let [outer* (atom nil)]
    (try
      (let [source (edn/read-string (slurp producer-ready-path))
            outer
            (viewer/start-remote-session!
             {:port 0
              :capability-token outer-token
              :max-document-bytes (* 1024 1024)
              :allowed-scenarios #{'ripple.remote.fixture/unused}
              :runtime-config {:worker-command ["/bin/false"]
                               :dir "."
                               :timeout-ms 1000}}
             (select-keys source
                          [:port :capability-token :session-instance-id]))]
        (reset! outer* outer)
        (spit outer-ready-path
              (str (trace/canonical-edn
                    {:status :ready
                     :port (:port outer)
                     :capability-token outer-token
                     :pinned-session-instance-id
                     (:session-instance-id source)})
                   "\n"))
        (await-release! release-path))
      (catch :default error
        (binding [*out* *err*]
          (println "remote Session consumer failed" (ex-message error)
                   (pr-str (ex-data error))))
        (System/exit 1))
      (finally
        (when-let [outer @outer*]
          (try (viewer/stop! outer) (catch :default _ nil)))))
    (System/exit 0)))
