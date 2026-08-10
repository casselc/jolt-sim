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
  (fs/normalize (fs/absolutize (fs/path value))))

(defn- same-path? [left right]
  (= left right))

(defn- publication-claim [output]
  (fs/path (fs/parent output)
           (str "." (fs/file-name output) ".publish-claim")))

(defn- acquire-publication! [output]
  (let [claim (publication-claim output)]
    (try
      ;; create-dir is the single atomic claim operation.  The directory is
      ;; deliberately retained: after success it prevents reuse of the
      ;; forensic output coordinate; after process failure it retains any
      ;; partial that reached the live filesystem. This is not a power-loss
      ;; durability claim; the protocol does not fsync files or directories.
      (fs/create-dir claim)
      claim
      (catch :default error
        (if (fs/exists? claim)
          (fail! invalid-arguments :publication-claimed)
          (throw error))))))

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
  then publishes its canonical EDN once to a previously absent output-path.
  No output write is attempted before official-run/document and
  official-run/canonical-edn both succeed. A process failure after claim
  acquisition and before the final same-filesystem rename leaves the retained
  publication claim and may leave its partial document for diagnosis, but
  never a partial output-path. This is not a host-crash, power-loss, or
  filesystem-durability guarantee.

  Exporters participating in this protocol cannot race or reuse an output
  coordinate: an atomic claim directory admits exactly one writer and is never
  removed. Existing output evidence is rejected before the claim and checked
  again by its sole owner before publication."
  [staging-path artifacts-path output-path]
  (let [staging-path (absolute-path staging-path)
        artifacts-path (absolute-path artifacts-path)
        output (absolute-path output-path)]
    ;; Exact normalized aliases get the more specific diagnosis. Every other
    ;; existing output (including a symlink or hardlink to an input) is then
    ;; rejected without consulting host inode layout or touching evidence. A
    ;; nonexistent output cannot already alias either existing input file.
    (when (or (same-path? output staging-path)
              (same-path? output artifacts-path))
      (fail! invalid-arguments :output-aliases-input))
    (when (fs/exists? output)
      (fail! invalid-arguments :output-exists))
    (let [staging (read-exact-edn (str staging-path))
          artifacts (read-exact-edn (str artifacts-path))
          result (document staging artifacts)
          encoded (official-run/canonical-edn result)
          claim (acquire-publication! output)
          partial (fs/path claim "official-run.edn.partial")]
      ;; All fallible document work precedes the irreversible claim. Once the
      ;; claim exists, every failure is intentionally retained and fail-closed.
      (when (fs/exists? output)
        (fail! invalid-arguments :output-exists-after-claim))
      (spit (str partial) (str encoded "\n"))
      (when-not (= result (official-run/read-edn (slurp (str partial))))
        (fail! invalid-staging :partial-verification-failed))
      (when (fs/exists? output)
        (fail! invalid-arguments :output-exists-after-write))
      (fs/move partial output)
      result)))

(defn -main [& args]
  (when-not (= 3 (count args))
    (fail! invalid-arguments :expected-staging-artifacts-output))
  (let [[staging-path artifacts-path output-path] args]
    (export! staging-path artifacts-path output-path)
    (println (str "official Maelstrom run evidence: " output-path))
    (flush)))
