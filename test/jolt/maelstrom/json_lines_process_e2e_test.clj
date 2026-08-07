(ns jolt.maelstrom.json-lines-process-e2e-test
  "Independent verifier for the retained stdin/stdout process evidence made by
  script/run-maelstrom-echo-e2e.sh. It parses wire JSON directly with
  clojure.data.json rather than calling the production framing adapter."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]))

(defonce ^:private artifact-root* (atom nil))

(defn- artifact-path [case-name file-name]
  (str @artifact-root* "/" case-name "/" file-name))

(defn- read-status [case-name]
  (parse-long (string/trim (slurp (artifact-path case-name "status")))))

(defn- read-wire-lines [case-name]
  (let [text (slurp (artifact-path case-name "stdout.jsonl"))]
    (if (string/blank? text)
      []
      (mapv #(json/read-str %) (string/split-lines text)))))

(def ^:private echo-payload
  {"greeting" "héllo, 世界 🌍"
   "false" false
   "empty" []
   "null" nil
   "nested" {"values" [0 255 "🚀"]}})

(deftest successful-process-preserves-the-exact-json-lines-contract
  (let [responses (read-wire-lines "success")]
    (is (= 0 (read-status "success")))
    (is (string/blank? (slurp (artifact-path "success" "stderr.log"))))
    (is (= 2 (count responses)))
    (is (= {"src" "n1" "dest" "c1"
            "body" {"type" "init_ok" "msg_id" 1 "in_reply_to" 1}}
           (first responses)))
    (is (= {"src" "n1" "dest" "c1"
            "body" {"type" "echo_ok" "msg_id" 2 "in_reply_to" 2
                    "echo" echo-payload}}
           (second responses)))))

(deftest malformed-second-line-fails-after-retaining-the-valid-prefix
  (let [responses (read-wire-lines "malformed")
        stderr (slurp (artifact-path "malformed" "stderr.log"))
        request-lines
        (string/split-lines
         (slurp (artifact-path "malformed" "request.jsonl")))
        hostile-line (second request-lines)]
    (is (not= 0 (read-status "malformed")))
    ;; The first complete request has exactly one complete reply. The broken
    ;; second line can neither disappear nor create a partial/extra JSON line.
    (is (= [{"src" "n1" "dest" "c1"
             "body" {"type" "init_ok" "msg_id" 1 "in_reply_to" 1}}]
           responses))
    (is (> (count hostile-line) 4096))
    (is (not (string/blank? stderr)))
    (is (string/includes? stderr "failed to parse JSON-lines input"))
    (is (< (count stderr) 2048))
    (is (not (string/includes? stderr hostile-line)))))

(defn -main [& args]
  (let [root (first args)]
    (when-not (and (string? root) (not (string/blank? root)))
      (println "JSON-lines process verifier requires one retained artifact root")
      (flush)
      (System/exit 2))
    (reset! artifact-root* root)
    (let [result (test/run-tests 'jolt.maelstrom.json-lines-process-e2e-test)
          failures (+ (:fail result) (:error result))]
      (println (str (:test result) " tests, " (:pass result)
                    " assertions passed"))
      (flush)
      (System/exit (if (zero? failures) 0 1)))))
