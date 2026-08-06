(ns jolt.sim.session-view
  "UI-neutral, in-process read/step adapter over one jolt.sim.session Session.

  This namespace is a thin shared inspection slice over the cooperative Session
  capability. It does not implement HTTP, a UI, a remote protocol, another
  scheduler, durable storage, or generic effects: every branch preview and
  transition delegates to the Session, and the only state it owns is the
  bounded optimistic retry budget used to keep one read coherent.

  A read-frame is a point-in-time view: the snapshot, the isolated branch
  previews, and the append-only journal tail from a validated integer cursor
  all describe the same session revision. A concurrent REPL step cannot mix
  revisions: the read is retried optimistically (bounded) until two
  consecutive snapshot reads agree, and fails closed with a typed error if
  coherence cannot be obtained.

  step-frame! applies one exact revision-scoped branch synchronously and
  returns an explicit result envelope. A stale branch is never silently
  rebased: the result includes a refreshed frame and requires the caller to
  choose again. Once a transition commits, its acknowledgment is returned even
  if the post-commit frame cannot be obtained, preventing an ambiguous retry."
  (:require [jolt.sim.session :as session]))

(def ^:private max-coherence-attempts 8)
(def max-journal-page-size
  "Maximum number of immutable session journal entries returned per frame."
  256)

;; The core logic operates on a small map of closures over the public Session
;; API. Public functions build this map from a real Session; tests build it
;; from scripted doubles so coherence failure and post-commit acknowledgment
;; are deterministically exercisable without leaking Session internals.
(defn- session-ops [session]
  {:snapshot (fn [] (session/snapshot session))
   :previews (fn [] (session/branches session))
   :journal (fn [] (session/journal session))
   :step! (fn [branch] (session/step! session branch))})

(defn- validate-cursor! [cursor]
  (when-not (and (integer? cursor) (not (neg? cursor)))
    (throw
     (ex-info "Session journal cursor must be a non-negative integer"
              {:type ::invalid-cursor
               :reason :not-a-non-negative-integer
               :cursor cursor}))))

(defn- validate-cursor-bound! [cursor journal-count]
  (when (> cursor journal-count)
    (throw
     (ex-info "Session journal cursor is ahead of the journal"
              {:type ::invalid-cursor
               :reason :ahead-of-journal
               :cursor cursor
               :journal-count journal-count}))))

(defn- journal-page [journal cursor journal-count]
  (validate-cursor-bound! cursor journal-count)
  (let [next-cursor (min journal-count (+ cursor max-journal-page-size))]
    {:cursor cursor
     :next-cursor next-cursor
     :count journal-count
     :page-size (- next-cursor cursor)
     :remaining? (< next-cursor journal-count)
     :entries (subvec (vec journal) cursor next-cursor)}))

(defn- coherent?
  "True when every read in one frame attempt describes the same session
  revision: the two snapshot reads agree, the journal read matches the
  snapshot's journal count, and every preview carries the snapshot revision."
  [snap previews journal snap-again]
  (and (= (:revision snap) (:revision snap-again))
       (= (get-in snap [:journal :count])
          (get-in snap-again [:journal :count]))
       (= (count journal) (get-in snap [:journal :count]))
       (= (:branches snap) (mapv :branch previews))
       (every? #(= (:revision snap) (get-in % [:branch :revision]))
               previews)))

(defn- build-frame [snap previews journal cursor]
  (let [journal-count (get-in snap [:journal :count])]
    {:jolt.sim.session-view/type :frame
     :kind :jolt.sim.kind/session-frame
     :revision (:revision snap)
     :status (:status snap)
     :projection (:projection snap)
     :branches (:branches snap)
     :previews previews
     :journal (journal-page journal cursor journal-count)}))

(defn- read-frame* [ops cursor]
  (validate-cursor! cursor)
  (loop [attempt 0]
    (let [snap ((:snapshot ops))
          previews ((:previews ops))
          journal ((:journal ops))
          snap-again ((:snapshot ops))]
      (if (coherent? snap previews journal snap-again)
        (build-frame snap previews journal cursor)
        (if (>= attempt (dec max-coherence-attempts))
          (throw
           (ex-info "Session frame coherence could not be obtained"
                    {:type ::coherence-failed
                     :attempts (inc attempt)
                     :max-attempts max-coherence-attempts}))
          (recur (inc attempt)))))))

(defn- plain-branch [branch]
  {:revision (:revision branch)
   :action [(nth (:action branch) 0) (nth (:action branch) 1)]})

(defn- frame-error [phase error]
  (let [data (ex-data error)
        error-type (:type data)]
    (cond-> {:type (if (keyword? error-type)
                     error-type
                     ::frame-unavailable)
             :phase phase}
      (integer? (:attempts data)) (assoc :attempts (:attempts data))
      (integer? (:max-attempts data))
      (assoc :max-attempts (:max-attempts data)))))

(defn- frame-after-command [ops cursor phase]
  (try
    {:frame (read-frame* ops cursor)
     :frame-error nil}
    (catch :default error
      {:frame nil
       :frame-error (frame-error phase error)})))

(defn- stale-result [ops cursor error]
  (let [data (ex-data error)]
    (merge
     {:jolt.sim.session-view/type :step-result
      :kind :jolt.sim.kind/session-step-result
      :status :stale
      :committed? false
      :ack nil
      :stale (select-keys data
                          [:type :expected-revision :actual-revision :branch])}
     (frame-after-command ops cursor :stale-refresh))))

(defn- step-frame* [ops branch cursor]
  (validate-cursor! cursor)
  (let [before ((:snapshot ops))
        _ (validate-cursor-bound! cursor (get-in before [:journal :count]))]
    (try
      (let [snapshot-after ((:step! ops) branch)
            ack {:branch (plain-branch branch)
                 :revision (:revision snapshot-after)}]
        (merge
         {:jolt.sim.session-view/type :step-result
          :kind :jolt.sim.kind/session-step-result
          :status :committed
          :committed? true
          :ack ack}
         ;; The step is already committed. Never throw from this point: an
         ;; exception would make the caller unable to distinguish a lost reply
         ;; from an uncommitted command and could cause duplicate execution.
         (frame-after-command ops cursor :post-commit)))
      (catch :default error
        (if (= :jolt.sim.session/stale-branch (:type (ex-data error)))
          (stale-result ops cursor error)
          (throw error))))))

(defn read-frame
  "Returns one coherent, closed inspection frame over an in-process Session.

  The frame is a point-in-time view: the snapshot (`:revision`, `:status`,
  `:projection`, `:branches`), the isolated branch previews (`:previews`), and
  the append-only journal tail
  (`:journal {:cursor :next-cursor :count :entries}`) all describe the same
  session revision. `cursor` is the number of journal
  entries the caller has already seen; it must be a non-negative integer no
  greater than the journal length at the coherent revision. Pass the returned
  `:next-cursor` to the next read to receive only later entries.

  A concurrent REPL step cannot mix revisions: the read is retried
  optimistically (bounded by `max-coherence-attempts`) until two consecutive
  snapshot reads agree, and fails closed with a `::coherence-failed` error if
  coherence cannot be obtained. A malformed or out-of-range cursor fails
  closed with `::invalid-cursor`."
  [session cursor]
  (read-frame* (session-ops session) cursor))

(defn step-frame!
  "Synchronously applies one exact revision-scoped branch and returns a closed
  command-result envelope.

  `branch` must be an exact `{:revision N :action [tag payload]}` map and
  `cursor` a validated non-negative integer. A committed result has
  `:committed? true`, an exact `:ack`, and normally a coherent `:frame`.
  Post-commit frame failure is data (`:frame nil`, typed `:frame-error`), never
  an exception that could invite an ambiguous duplicate retry.

  A stale revision is never applied automatically, even if the action identity
  remains enabled: another task may have changed shared state and invalidated
  the preview the caller chose. The result instead has `:status :stale`,
  `:committed? false`, the original typed stale coordinates, and a refreshed
  frame from which a human or agent must explicitly choose again. Any other
  pre-commit session error (for example `::invalid-branch` or the kernel's
  `::invalid-machine-action` on a terminal or disabled action) propagates
  unchanged."
  [session branch cursor]
  (step-frame* (session-ops session) branch cursor))
