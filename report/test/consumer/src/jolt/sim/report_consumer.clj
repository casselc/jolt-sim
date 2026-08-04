(ns jolt.sim.report-consumer
  (:require [clojure.string :as string]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]))

(defn- sample-document []
  (let [projection [:jolt.sim.value/map []]]
    (trace/document
     [(trace/initial-event projection)
      (trace/completed-event 0 0 projection)])))

(defn -main [& _]
  (let [html (report/trace->html (sample-document))]
    (when-not (and (string/includes? html "jolt-sim trace report")
                   (string/includes? html "run/completed"))
      (throw
       (ex-info
        "Built report consumer produced incomplete HTML"
        {:type ::incomplete-report
         :length (count html)})))
    (println "REPORT_CONSUMER_PASS")
    (flush)))
