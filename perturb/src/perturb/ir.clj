(ns perturb.ir
  "Capture REAL Jolt IR for a namespace, from inside a running Jolt program.

  PERTURB-DESIGN §2.1 makes two claims about `jolt-core/jolt/ir.clj` from source
  reading, and §4 records that this session's record on source-reading inferences
  is poor and that they stay untested until *a checker walks real IR*. This
  namespace is how the checker gets real IR without modifying Jolt.

  HOW. The Chez compile spine (`host/chez/compile-eval.ss:308`) is

      (let* ((ctx (make-analyze-ctx ns)) (node (jolt-ce-analyze ctx form)))
        (jolt-ce-emit (jolt-ce-run-passes node ctx)))

  `make-analyze-ctx` builds a Scheme record (`chez-actx`) that is NOT reachable
  from Jolt-level code, so `jolt.analyzer/analyze` cannot simply be called. Both
  `jolt-ce-analyze` and `jolt-ce-run-passes` are `var-deref`'d at host load time,
  so redefining those two vars has no effect either.

  What IS reachable: `jolt.passes/run-passes` calls `jolt.passes.numeric/annotate`
  on every analyzed top-level form, through that var's cell. Rebinding the var's
  root with `alter-var-root` puts a tap on the compile spine: every top-level
  form compiled after `install!` passes its post-passes IR through this
  namespace on the way to the back end. Nothing in `/home/user/jolt` changes; the
  hook is a var rebinding made by perturb code, at perturb's own request.

  WHAT THIS COSTS, STATED PLAINLY (INHERITED I17). The tap is a private
  implementation detail of Jolt's pass pipeline, not an interface. It is the
  IR *after* const-folding (`jolt.passes/run-passes`'s :else branch on this
  path), not the raw analyzer output — the emitter's input, which is the right
  thing to check but is not the same tree the analyzer produced. And a namespace
  already loaded before `install!` is never re-analyzed, so capture order is
  load order and `install!` has to run first.")

(require '[jolt.passes.numeric :as jnum])
(require '[clojure.string :as str])

(def captured
  "\"ns/name\" -> the :def IR node the back end was handed."
  (atom {}))

(def capture-order (atom []))

(def ^:private installed (atom false))

(defn install!
  "Tap the compile spine. Idempotent. Must run before the namespaces to be
  checked are required."
  []
  (when (not @installed)
    (reset! installed true)
    (alter-var-root
      (var jnum/annotate)
      (fn [old]
        (fn [node]
          (when (= :def (:op node))
            (let [k (str (:ns node) "/" (:name node))]
              (when (not (contains? @captured k))
                (swap! capture-order conj k))
              (swap! captured assoc k node)))
          (old node)))))
  @installed)

(defn capture!
  "Install the tap, then require each namespace in `ns-syms` so its forms are
  analyzed under it. Returns the number of :def nodes captured."
  [ns-syms]
  (install!)
  (doseq [s ns-syms] (require s))
  (count @captured))

(defn defs-in
  "The captured :def nodes of one namespace, in load order."
  [ns-name]
  (vec (remove nil?
               (map (fn [k]
                      (let [n (get @captured k)]
                        (when (= ns-name (:ns n)) n)))
                    @capture-order))))

(defn short-file
  "Trim an absolute source path down to the part under the project root."
  [f]
  (if (nil? f)
    "?"
    (let [i (str/index-of f "/perturb/src/")]
      (if (nil? i) f (subs f (inc i))))))

(defn site
  "Render a :pos annotation as file:line:col."
  [pos]
  (if (nil? pos)
    "<no position>"
    (str (short-file (:file pos)) ":" (:line pos) ":" (:column pos))))
