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
        malformed-staging-path (str (fs/path dir "malformed-staging.edn"))
        artifacts-path (str (fs/path dir "artifacts.edn"))
        output-path (str (fs/path dir "official-run.edn"))
        claim-path (str (fs/path dir ".official-run.edn.publish-claim"))
        valid-staging (staging 1 [(operation 0)])]
    (spit staging-path (str (pr-str valid-staging) "\n"))
    (spit malformed-staging-path "{:not :staging}\n")
    (spit artifacts-path (str (pr-str artifacts) "\n"))
    ;; Invalid staging cannot create the previously absent final document.
    (is (= export/invalid-staging
           (:type (thrown-data
                   #(export/export! malformed-staging-path
                                    artifacts-path
                                    output-path)))))
    (is (false? (fs/exists? output-path)))
    (is (false? (fs/exists? claim-path)))
    (export/export! staging-path artifacts-path output-path)
    (let [encoded (slurp output-path)
          valid? (try
                   (official-run/read-edn encoded)
                   true
                   (catch :default _ false))]
      (is valid?))
    (is (fs/directory? claim-path))
    ;; Even if the final evidence is moved elsewhere, this coordinate remains
    ;; consumed and cannot silently acquire a different document.
    (fs/delete output-path)
    (is (= :publication-claimed
           (:reason (thrown-data #(export/export! staging-path artifacts-path
                                                   output-path)))))
    (is (false? (fs/exists? output-path)))
    (fs/delete-tree dir)))

(deftest existing-output-evidence-is-never-replaced
  (let [dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-official-run-existing-test-"}))
        staging-path (str (fs/path dir "staging.edn"))
        artifacts-path (str (fs/path dir "artifacts.edn"))
        output-path (str (fs/path dir "official-run.edn"))
        claim-path (str (fs/path dir ".official-run.edn.publish-claim"))]
    (spit staging-path (str (pr-str (staging 1 [(operation 0)])) "\n"))
    (spit artifacts-path (str (pr-str artifacts) "\n"))
    (spit output-path "prior evidence")
    (is (= :output-exists
           (:reason (thrown-data #(export/export! staging-path artifacts-path
                                                   output-path)))))
    (is (= "prior evidence" (slurp output-path)))
    (is (false? (fs/exists? claim-path)))
    (fs/delete-tree dir)))

(deftest an-existing-publication-claim-fails-closed
  (let [dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-official-run-claimed-test-"}))
        staging-path (str (fs/path dir "staging.edn"))
        artifacts-path (str (fs/path dir "artifacts.edn"))
        output-path (str (fs/path dir "official-run.edn"))
        claim-path (str (fs/path dir ".official-run.edn.publish-claim"))]
    (spit staging-path (str (pr-str (staging 1 [(operation 0)])) "\n"))
    (spit artifacts-path (str (pr-str artifacts) "\n"))
    (fs/create-dir claim-path)
    (spit (str (fs/path claim-path "official-run.edn.partial")) "retained")
    (is (= :publication-claimed
           (:reason (thrown-data #(export/export! staging-path artifacts-path
                                                   output-path)))))
    (is (false? (fs/exists? output-path)))
    (is (= "retained"
           (slurp (str (fs/path claim-path "official-run.edn.partial")))))
    (fs/delete-tree dir)))

(deftest concurrent-exporters-admit-exactly-one-writer
  (let [dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-official-run-race-test-"}))
        staging-path (str (fs/path dir "staging.edn"))
        artifacts-path (str (fs/path dir "artifacts.edn"))
        output-path (str (fs/path dir "official-run.edn"))
        ready-a (promise)
        ready-b (promise)
        release (promise)
        attempt (fn [ready]
                  (deliver ready true)
                  @release
                  (try
                    (export/export! staging-path artifacts-path output-path)
                    :published
                    (catch :default error
                      (:reason (ex-data error)))))
        _ (spit staging-path
                (str (pr-str (staging 1 [(operation 0)])) "\n"))
        _ (spit artifacts-path (str (pr-str artifacts) "\n"))
        writer-a (future (attempt ready-a))
        writer-b (future (attempt ready-b))]
    @ready-a
    @ready-b
    (deliver release true)
    (let [outcomes [@writer-a @writer-b]
          losers (remove #{:published} outcomes)]
      (is (= 1 (count (filter #{:published} outcomes))))
      ;; A loser observed either the completed publication at the cheap
      ;; precheck or the already-owned atomic claim. Both are fail-closed.
      (is (= 1 (count losers)))
      (is (contains? #{:output-exists :publication-claimed}
                     (first losers))))
    (is (some? (official-run/read-edn (slurp output-path))))
    (is (fs/directory?
         (str (fs/path dir ".official-run.edn.publish-claim"))))
    (fs/delete-tree dir)))

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
