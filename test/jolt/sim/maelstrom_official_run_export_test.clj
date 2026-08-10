(ns jolt.sim.maelstrom-official-run-export-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.fs :as fs]
            [jolt.sim.maelstrom.official-run :as official-run]
            [jolt.sim.maelstrom.official-run-export :as export]))

(def ^:private digest (apply str (repeat 64 "b")))

(defn- operation [index]
  {:type (if (even? index) :invoke :ok)
   :f :read
   :process (mod index 5)
   :time index
   :value {:messages [index]}})

(defn- staging [total operations]
  {:run {:profile :broadcast-healthy
         :workload :broadcast
         :parameters {:node-count 5 :topology :tree4}}
   :outcome {:status :passed
             :exit 0
             :official-valid? true
             :workload-valid? true
             :checks {:positive-workload? true}
             :stats {:operation-count total}}
   :history {:total-count total
             :artifact "history.edn"
             :operations operations}})

(def ^:private artifacts
  [{:name "history.edn" :role :history :bytes 4096 :sha256 digest}
   {:name "results.edn" :role :results :bytes 512 :sha256 digest}])

(defn- thrown-data [f]
  (try (f) nil (catch :default error (ex-data error))))

(deftest exporter-delegates-canonicalization-to-official-run
  (let [document (export/document (staging 2 [(operation 0) (operation 1)])
                                  artifacts)
        encoded (official-run/canonical-edn document)
        restored (official-run/read-edn encoded)]
    (is (= document restored))
    (is (= {:node-count 5 :topology :tree4}
           (get-in (official-run/read-page document)
                   [:header :run :parameters])))
    (is (= {:messages [0]}
           (get-in (official-run/read-page document)
                   [:operations 0 :value])))))

(deftest exporter-defensively-caps-staged-operations
  (let [supplied-count (inc official-run/max-captured-operations)
        document (export/document
                  (staging (+ supplied-count 10)
                           (mapv operation (range supplied-count)))
                  artifacts)
        history (get-in (official-run/read-page document) [:header :history])]
    (is (= official-run/max-captured-operations
           (:captured-count history)))
    (is (= (+ supplied-count 10) (:total-count history)))
    (is (true? (:truncated? history)))))

(deftest malformed-staging-fails-before-the-document-boundary
  (is (= :wrong-staging-shape
         (:reason
          (thrown-data #(export/document (assoc (staging 0 []) :extra true)
                                          artifacts)))))
  (is (= :invalid-history
         (:reason
          (thrown-data #(export/document
                         (assoc-in (staging 0 []) [:history :operations] {})
                         artifacts))))))

(deftest export-writes-only-after-complete-validation
  (let [dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-official-run-export-test-"}))
        staging-path (str (fs/path dir "staging.edn"))
        artifacts-path (str (fs/path dir "artifacts.edn"))
        output-path (str (fs/path dir "official-run.edn"))
        valid-staging (staging 1 [(operation 0)])]
    (spit staging-path (str (pr-str valid-staging) "\n"))
    (spit artifacts-path (str (pr-str artifacts) "\n"))
    (spit output-path "sentinel")
    (let [failure (thrown-data
                   #(export/export! staging-path
                                    artifacts-path
                                    output-path))]
      ;; The first attempt is valid, so it replaces the sentinel with one
      ;; validated canonical document.
      (is (nil? failure)))
    (let [encoded (slurp output-path)
          valid? (try
                   (official-run/read-edn encoded)
                   true
                   (catch :default _ false))]
      (is valid?))
    ;; A malformed retry must not touch the already-published document.
    (let [before (slurp output-path)]
      (spit staging-path "{:not :staging}\n")
      (is (= export/invalid-staging
             (:type (thrown-data
                     #(export/export! staging-path
                                      artifacts-path
                                      output-path)))))
      (let [unchanged? (= before (slurp output-path))]
        (is unchanged?)
        (if unchanged?
          (fs/delete-tree dir)
          (println (str "retained failed exporter test directory: " dir)))))))

(deftest output-may-not-alias-forensic-inputs
  (let [dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-official-run-alias-test-"}))
        staging-path (str (fs/path dir "staging.edn"))
        artifacts-path (str (fs/path dir "artifacts.edn"))]
    (spit staging-path (str (pr-str (staging 1 [(operation 0)])) "\n"))
    (spit artifacts-path (str (pr-str artifacts) "\n"))
    (doseq [output-path [staging-path
                         artifacts-path
                         (str (fs/path dir "." "staging.edn"))]]
      (is (= :output-aliases-input
             (:reason
              (thrown-data #(export/export! staging-path artifacts-path
                                             output-path))))))
    (is (= (staging 1 [(operation 0)])
           (read-string (slurp staging-path))))
    (is (= artifacts (read-string (slurp artifacts-path))))
    (fs/delete-tree dir)))

(deftest bare-relative-output-is-published-from-its-own-directory
  (let [output-path (str ".official-run-export-relative-"
                         (System/currentTimeMillis) ".edn")
        dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-official-run-relative-test-"}))
        staging-path (str (fs/path dir "staging.edn"))
        artifacts-path (str (fs/path dir "artifacts.edn"))]
    (spit staging-path (str (pr-str (staging 1 [(operation 0)])) "\n"))
    (spit artifacts-path (str (pr-str artifacts) "\n"))
    (try
      (export/export! staging-path artifacts-path output-path)
      (is (some? (official-run/read-edn (slurp output-path))))
      (finally
        (fs/delete-if-exists output-path)
        (fs/delete-tree dir)))))
