(ns jolt.sim.viewer-remote-session-producer
  "Separate-process fixture that owns the inner Session and Ripple listener."
  (:require [jolt.sim.kernel :as kernel]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]))

(def ^:private token "ripple-remote-producer-capability-0001")

(defn- sim-config []
  {:tasks {0 (kernel/runnable :finish)}
   :world {:fixture :separate-process}
   :step (fn [{:keys [world]} _]
           (-> (kernel/step-complete :done)
               (kernel/with-world world)
               (kernel/at-site {:ns 'ripple.remote.fixture
                                :phase :finish})))})

(defn- serve-until-release! [sim-session step-path stepped-path release-path]
  (let [deadline (+ (System/nanoTime) (* 120000 1000000))]
    (loop [stepped? false]
      (cond
        (.exists (java.io.File. release-path)) true

        (and (not stepped?) (.exists (java.io.File. step-path)))
        (let [branch (first (session/actions sim-session))
              snapshot (session/step! sim-session branch)]
          (spit stepped-path
                (str (trace/canonical-edn
                      {:status :stepped
                       :branch branch
                       :revision (:revision snapshot)
                       :journal-count (get-in snapshot [:journal :count])})
                     "\n"))
          (recur true))

        (< (System/nanoTime) deadline) (do (Thread/sleep 10)
                                           (recur stepped?))
        :else (throw (ex-info "remote Session fixture release timed out"
                              {:type ::release-timeout
                               :release-path release-path}))))))

(defn -main [ready-path step-path stepped-path release-path port-text instance-id]
  (let [server* (atom nil)]
    (try
      (let [port (parse-long port-text)
            _ (when-not (and port (<= 0 port 65535))
                (throw (ex-info "invalid fixture port"
                                {:type ::invalid-port :port port-text})))
            sim-session (session/start (sim-config))
            server
            (viewer/start-session!
             {:port port
              :capability-token token
              :session-instance-id instance-id
              :max-document-bytes (* 1024 1024)
              :allowed-scenarios #{'ripple.remote.fixture/unused}
              :runtime-config {:worker-command ["/bin/false"]
                               :dir "."
                               :timeout-ms 1000}}
             sim-session)]
        (reset! server* server)
        (spit ready-path
              (str (trace/canonical-edn
                    {:status :ready
                     :port (:port server)
                     :capability-token token
                     :session-instance-id instance-id})
                   "\n"))
        (serve-until-release! sim-session step-path stepped-path release-path))
      (catch :default error
        (binding [*out* *err*]
          (println "remote Session producer failed" (ex-message error)
                   (pr-str (ex-data error))))
        (System/exit 1))
      (finally
        (when-let [server @server*]
          (try (viewer/stop! server) (catch :default _ nil)))))
    (System/exit 0)))
