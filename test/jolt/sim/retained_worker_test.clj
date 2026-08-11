(ns jolt.sim.retained-worker-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.fs :as fs]
            [jolt.sim.retained-worker :as worker]
            [jolt.sim.trace :as trace]))

(defn ^{:jolt.sim/retained-adapter true} test-adapter
  [{:keys [input serve!] :as control}]
  (when-not (= #{:input :serve!} (set (keys control)))
    (throw (ex-info "bad control" {:control-keys (set (keys control))})))
  (serve!
   (fn [command]
     (case (:op command)
       :inspect {:terminal? false :value {:input input}}
       :fail (throw (ex-info "command rejected" {:secret :not-transported}))
       :stop {:terminal? true :value {:stopped true}}
       (throw (ex-info "unknown command" {:op (:op command)}))))))

(defn- wait-for-path [path timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (fs/exists? path) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- write-document! [path document]
  (let [partial (str path ".partial")]
    (spit partial (trace/canonical-edn document))
    (fs/move partial path {:atomic-move true})))

(defn- command-document [sequence command]
  {:jolt.sim.retained/protocol 1
   :jolt.sim.retained/instance-id "worker-test"
   :jolt.sim.retained/sequence sequence
   :jolt.sim.retained/command (trace/canonical-value command)})

(defn- read-document [path]
  (edn/read-string (slurp path)))

(defn- retain-artifacts! [dir]
  (println "retained worker test artifacts:" dir)
  (flush))

(deftest retained-worker-publishes-sequential-receipts-and-survives-app-failure
  (let [dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-retained-worker-test-"}))
        startup (str (fs/path dir "startup.edn"))
        ready (str (fs/path dir "ready.edn"))
        commands (str (fs/path dir "commands"))
        receipts (str (fs/path dir "receipts"))
        terminal (str (fs/path dir "terminal.edn"))]
    (try
      (fs/create-dirs commands)
      (fs/create-dirs receipts)
      (spit startup
            (trace/canonical-edn
             {:jolt.sim.retained/protocol 1
              :jolt.sim.retained/instance-id "worker-test"
              :jolt.sim.retained/adapter
              'jolt.sim.retained-worker-test/test-adapter
              :jolt.sim.retained/input (trace/canonical-value {:seed 7})}))
      (let [running (future
                      (worker/run-worker! startup ready commands receipts
                                          terminal))]
        (is (true? (wait-for-path ready 2000)))
        (is (= :ready (:jolt.sim.retained/status (read-document ready))))

        (let [path (str (fs/path commands
                                 "command-00000000000000000000.edn"))
              receipt (str (fs/path receipts
                                    "receipt-00000000000000000000.edn"))]
          (write-document! path (command-document 0 {:op :fail}))
          (is (true? (wait-for-path receipt 2000)))
          (let [document (read-document receipt)
                error (trace/restore-value
                       (:jolt.sim.retained/error document))]
            (is (= 0 (:jolt.sim.retained/sequence document)))
            (is (= :failed (:jolt.sim.retained/status document)))
            (is (= :application-command (:phase error)))
            (is (not (.contains (pr-str error) ":secret")))))

        (let [path (str (fs/path commands
                                 "command-00000000000000000001.edn"))
              receipt (str (fs/path receipts
                                    "receipt-00000000000000000001.edn"))]
          (write-document! path (command-document 1 {:op :inspect}))
          (is (true? (wait-for-path receipt 2000)))
          (let [document (read-document receipt)]
            (is (= :completed (:jolt.sim.retained/status document)))
            (is (= {:input {:seed 7}}
                   (trace/restore-value
                    (:jolt.sim.retained/value document))))))

        (let [path (str (fs/path commands
                                 "command-00000000000000000002.edn"))
              receipt (str (fs/path receipts
                                    "receipt-00000000000000000002.edn"))]
          (write-document! path (command-document 2 {:op :stop}))
          (is (true? (wait-for-path receipt 2000)))
          (is (= :completed
                 (:jolt.sim.retained/status (read-document receipt)))))

        (is (not= ::timeout (deref running 2000 ::timeout)))
        (is (true? (wait-for-path terminal 2000)))
        (is (= :completed
               (:jolt.sim.retained/status (read-document terminal)))))
      (finally
        (retain-artifacts! dir)))))
