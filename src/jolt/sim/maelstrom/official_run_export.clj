(ns jolt.sim.maelstrom.official-run-export
  "Trusted local exporter for retained official Maelstrom evidence.

  The official JVM checker writes one bounded ordinary-EDN staging value after
  all of its acceptance checks pass.  The shell runner separately supplies
  logical artifact descriptors computed from the retained raw files.  This
  namespace combines those values through official-run/document, uses its
  canonical codec, and writes the final document only after validation.

  It does not read, reinterpret, copy, or delete Maelstrom's raw result,
  history, node, network, or process artifacts."
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]
            [jolt.sim.maelstrom.official-run :as official-run]))

(def invalid-staging ::invalid-staging)
(def invalid-arguments ::invalid-arguments)

(def ^:private staging-keys #{:run :outcome :history})
(def ^:private staging-history-keys
  #{:total-count :artifact :operations})
(def ^:private end-of-input (atom nil))

(defn- fail! [type reason]
  (throw (ex-info "Official Maelstrom run export input is malformed"
                  {:type type :reason reason})))

(defn- exact-map? [expected-keys value]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- absolute-path [value]
  (fs/absolutize (fs/path value)))

(defn- same-file-or-path? [left right]
  (or (= left right)
      (and (fs/exists? left)
           (fs/exists? right)
           (fs/same-file? left right))))

(defn- read-exact-edn [path]
  (let [text (slurp path)]
    (try
      (let [reader (__string-reader text)
            [value _] (read+string reader false end-of-input)
            [trailing _] (read+string reader false end-of-input)]
        (when (identical? value end-of-input) (fail! invalid-staging :empty-input))
        (when-not (identical? trailing end-of-input)
          (fail! invalid-staging :trailing-input))
        (edn/read-string text))
      (catch :default error
        (if (= invalid-staging (:type (ex-data error)))
          (throw error)
          (fail! invalid-staging :unreadable-edn))))))

(defn document
  "Builds and validates one official-run document from trusted staging data.

  Staging history may contain at most a small bounded prefix chosen by the JVM
  checker.  This function defensively caps that prefix again at the current
  official-run/max-captured-operations, derives captured/truncated facts, and
  delegates every document-domain check and canonical projection to
  official-run/document."
  [staging artifacts]
  (when-not (exact-map? staging-keys staging)
    (fail! invalid-staging :wrong-staging-shape))
  (let [history (:history staging)]
    (when-not (and (exact-map? staging-history-keys history)
                   (integer? (:total-count history))
                   (not (neg? (:total-count history)))
                   (vector? (:operations history)))
      (fail! invalid-staging :invalid-history))
    (let [operations (:operations history)
          captured (subvec operations
                           0
                           (min (count operations)
                                official-run/max-captured-operations))
          projected-history
          {:total-count (:total-count history)
           :truncated? (< (count captured) (:total-count history))
           :artifact (:artifact history)
           :operations captured}]
      (official-run/document (:run staging)
                             (:outcome staging)
                             projected-history
                             artifacts))))

(defn export!
  "Reads staging and artifact descriptor EDN, validates the complete document,
  then atomically publishes its canonical EDN to output-path. No output write
  is attempted before official-run/document and official-run/canonical-edn
  both succeed. A crash before the final same-directory rename can leave a
  complete temporary file for diagnosis, but never a partial output-path."
  [staging-path artifacts-path output-path]
  (let [staging-path (absolute-path staging-path)
        artifacts-path (absolute-path artifacts-path)
        output (absolute-path output-path)]
    (when (or (same-file-or-path? output staging-path)
              (same-file-or-path? output artifacts-path))
      (fail! invalid-arguments :output-aliases-input))
    (let [staging (read-exact-edn (str staging-path))
        artifacts (read-exact-edn (str artifacts-path))
        result (document staging artifacts)
        encoded (official-run/canonical-edn result)
        temporary (fs/create-temp-file
                   {:dir (fs/parent output)
                    :prefix ".official-run-"
                    :suffix ".tmp"})]
      (spit (str temporary) (str encoded "\n"))
      (fs/move temporary output
               {:replace-existing true :atomic-move true})
      result)))

(defn -main [& args]
  (when-not (= 3 (count args))
    (fail! invalid-arguments :expected-staging-artifacts-output))
  (let [[staging-path artifacts-path output-path] args]
    (export! staging-path artifacts-path output-path)
    (println (str "official Maelstrom run evidence: " output-path))
    (flush)))
