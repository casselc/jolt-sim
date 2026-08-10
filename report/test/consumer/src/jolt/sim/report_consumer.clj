(ns jolt.sim.report-consumer
  (:require [clojure.string :as string]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.maelstrom.official-run :as official-run]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]))

(defn- sample-document []
  (let [projection [:jolt.sim.value/map []]]
    (trace/document
     [(trace/initial-event projection)
      (trace/completed-event 0 0 projection)])))

(defn- sample-case-outcome-document []
  (case-outcome/document
   {:scenario 'jolt.sim.report-consumer/standalone-case
    :mode :hermetic
    :input {:payload [0 127 128 255]}
    :schedule nil}
   {:status :completed
    :result {:application {:commands 1}
             :clean? {:memory true :sqlite true :posix true}}
    :exit 0}
   [{:id :standalone/report
     :status :pass
     :detail nil
     :index nil}]))

(defn- sample-official-run-document []
  (official-run/document
   {:profile :official :workload :echo :parameters {}}
   {:status :passed :exit 0 :official-valid? true :workload-valid? true
    :checks {:valid? true} :stats {}}
   {:total-count 0 :truncated? false :artifact "history.edn"
    :operations []}
   [{:name "history.edn" :role :history :bytes 0
     :sha256 (apply str (repeat 64 "0"))}]))

(defn -main [& _]
  (let [trace-html (report/trace->html (sample-document))
        case-html (report/case-outcome->html
                   (sample-case-outcome-document))
        official-html (report/official-run->html
                       (sample-official-run-document))]
    (when-not (and (string/includes? trace-html "jolt-sim trace report")
                   (string/includes? trace-html "run/completed")
                   (string/includes? case-html "jolt-sim case report")
                   (string/includes? case-html "standalone-case")
                   (string/includes? case-html ":standalone/report")
                   (string/includes? case-html ":application")
                   (string/includes? official-html
                                     "official Maelstrom run report")
                   (string/includes? official-html ":echo"))
      (throw
       (ex-info
        "Built report consumer produced incomplete report HTML"
        {:type ::incomplete-report
         :trace-length (count trace-html)
         :case-length (count case-html)
         :official-length (count official-html)})))
    (println "REPORT_CONSUMER_PASS")
    (flush)))
