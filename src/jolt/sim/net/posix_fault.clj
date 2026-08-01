(ns jolt.sim.net.posix-fault
  "First boundary frontend for jolt.sim.fault over the POSIX loopback world.

  Wraps the plain foreign-handlers map returned by
  jolt.sim.net.posix-loopback/foreign-handlers and interposes only the existing
  poll handler. Every poll call atomically steps a pure fault director with a
  canonical {:boundary :posix :operation :poll} attempt whose :attempt-id is the
  frontend-owned per-poll ordinal. When no rule fires, the exact original poll
  descriptor is delegated once and its captured result is returned unchanged.
  Exactly one frontend-owned outcome shape is accepted at construction:
  {:kind :captured-error :errno :eintr}. A target without an exact positive
  EINTR value, or a plan containing any other outcome, is rejected before a
  frontend is returned. When the accepted rule fires, poll returns the captured
  [-1 target-EINTR] pair without invoking the modeled poll.

  This is not a socket, poll, jolt.net, HTTP, or SQLite implementation. It
  only composes the existing POSIX loopback foreign handler set with the pure
  fault director at the poll seam. Director transitions and evidence
  publication are serialized across concurrent poll callers on a frontend-owned
  lock; the lock is never held while a delegated poll may park."
  (:require [jolt.sim.fault :as fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.trace :as trace]))

(def invalid-frontend-input :jolt.sim.net.posix-fault/invalid-frontend-input)
(def unsupported-fired-outcome :jolt.sim.net.posix-fault/unsupported-fired-outcome)
(def missing-target-errno :jolt.sim.net.posix-fault/missing-target-errno)

(def ^:private world-type :jolt.sim.net.posix-loopback/world)
(def ^:private frontend-type ::frontend)
(def ^:private director-type-key :jolt.sim.fault/type)
(def ^:private director-type :jolt.sim.fault/director)
(def ^:private frontend-keys #{:world :lock :state :type})
(def ^:private state-keys
  #{:next-attempt :initial-director :director :history})

;; Canonical attempt identity. The attempt-id is a stable trace-domain pair so
;; evidence history is byte-stable and unambiguous about which poll attempt
;; fired. :boundary and :operation are the natural keys a fault-plan matcher
;; composes on.
(def ^:private attempt-boundary :posix)
(def ^:private attempt-operation :poll)
(def ^:private attempt-id-tag :jolt.sim.net.posix-fault/poll)

;; The single frontend-owned outcome shape this slice interprets.
(def ^:private captured-error-outcome-keys #{:kind :errno})

(defn- fail! [type reason data]
  (throw
   (ex-info (str "jolt.sim.net.posix-fault " (name reason))
            (assoc data :type type :reason reason))))

(declare interpret-fired-outcome!)

(defn- director-from [plan-or-director]
  (fault/validate-director
   (cond
     (vector? plan-or-director)
     (fault/director plan-or-director)

     (and (map? plan-or-director)
          (= director-type (get plan-or-director director-type-key)))
     plan-or-director

     :else
     (fail! invalid-frontend-input :plan-or-director-required
            {:provided-class (str (class plan-or-director))}))))

(defn- validate-director-outcomes! [world director]
  ;; A POSIX poll frontend has one closed outcome algebra. Validate every rule
  ;; before admitting a frontend, not only when a rule becomes eligible, so a
  ;; valid frontend can publish one ordinal/evidence entry for every poll call.
  (doseq [rule (:rules director)]
    (interpret-fired-outcome!
     world (trace/restore-value (:outcome rule))))
  director)

(defn- require-frontend! [value]
  (when-not (and (map? value)
                 (= frontend-type (:type value))
                 (= frontend-keys (set (keys value)))
                 (map? (:world value))
                 (= world-type (get-in value [:world :type]))
                 (some? (:lock value))
                 (some? (:state value)))
    (fail! invalid-frontend-input :frontend-required
           {:provided-class (str (class value))}))
  ;; The world owner validates its exact closed shape and live top-level state.
  ;; This rejects a forged map carrying only the public world marker before a
  ;; handler registry or target-specific poll signature can be manufactured.
  (posix/validate-world (:world value))
  value)

(defn- replay-history! [initial-director history]
  ;; Evidence is retained canonically. Replaying the frontend's one fixed
  ;; attempt shape proves exact evidence shape, sequential attempt IDs, and
  ;; coherence between the initial director, history, and current counters.
  (loop [ordinal 1
         director initial-director
         remaining history]
    (if (seq remaining)
      (let [attempt {:attempt-id [attempt-id-tag ordinal]
                     :boundary attempt-boundary
                     :operation attempt-operation}
            stepped (fault/step director attempt)
            expected (trace/canonical-value (:evidence stepped))]
        (when-not (= expected (first remaining))
          (fail! invalid-frontend-input :invalid-frontend-history
                 {:attempt-ordinal ordinal}))
        (recur (inc ordinal) (:director stepped) (next remaining)))
      director)))

(defn- read-state! [frontend]
  (require-frontend! frontend)
  (let [state
        (try
          @(:state frontend)
          (catch :default _
            (fail! invalid-frontend-input :invalid-frontend-state {})))]
    (when-not (and (map? state)
                   (= state-keys (set (keys state)))
                   (integer? (:next-attempt state))
                   (pos? (:next-attempt state))
                   (vector? (:history state))
                   (= (dec (:next-attempt state)) (count (:history state)))
                   (every? trace/canonical-form? (:history state)))
      (fail! invalid-frontend-input :invalid-frontend-state {}))
    (let [initial (fault/validate-director (:initial-director state))
          director (fault/validate-director (:director state))]
      (validate-director-outcomes! (:world frontend) initial)
      (validate-director-outcomes! (:world frontend) director)
      (when-not (= director (replay-history! initial (:history state)))
        (fail! invalid-frontend-input :incoherent-frontend-state {})))
    state))

(defn- read-hot-state! [frontend]
  ;; Handler maps are created only after read-state! has validated the complete
  ;; frontend, world, and replay history. Poll then owns the only normal writes
  ;; to this state atom. Keep the per-poll check bounded to the closed state
  ;; shape plus the current director/outcome contracts; replaying the complete
  ;; history or rebuilding world-handler evidence here would make N polls
  ;; quadratic and can consume an application's real monotonic deadline.
  (let [state
        (try
          @(:state frontend)
          (catch :default _
            (fail! invalid-frontend-input :invalid-frontend-state {})))]
    (when-not (and (map? state)
                   (= state-keys (set (keys state)))
                   (integer? (:next-attempt state))
                   (pos? (:next-attempt state))
                   (vector? (:history state))
                   (= (dec (:next-attempt state)) (count (:history state))))
      (fail! invalid-frontend-input :invalid-frontend-state {}))
    (fault/validate-director (:initial-director state))
    (validate-director-outcomes!
     (:world frontend) (fault/validate-director (:director state)))
    state))

(defn frontend
  "Returns a POSIX poll fault frontend wrapping the loopback ``world`` and a
  closed fault ``plan-or-director``.

  ``plan-or-director`` is either a vector plan accepted by
  jolt.sim.fault/director or an already-built, fully validated director map.
  Every rule must carry exactly ``{:kind :captured-error :errno :eintr}``, and
  the supplied POSIX world must expose an exact positive target EINTR value;
  invalid policy is rejected before the frontend is returned.
  The frontend owns
  deterministic per-poll attempt ordinals (starting at 1), the next director
  state, and an exact stable evidence history. A pre-built director is
  validated before the frontend is returned, so snapshots cannot expose
  forged state before the first poll.

  The returned frontend is ordinary data plus one host lock object and one
  state atom; snapshot and evidence expose neither."
  [world plan-or-director]
  (when-not (and (map? world) (= world-type (:type world)))
    (fail! invalid-frontend-input :posix-loopback-world-required
           {:provided-class (str (class world))}))
  (posix/validate-world world)
  (let [director
        (validate-director-outcomes!
         world (director-from plan-or-director))]
    {:world world
     :lock (Object.)
     :state (atom {:next-attempt 1
                   :initial-director director
                   :director director
                   :history []})
     :type frontend-type}))

(defn- claim-and-step! [frontend]
  ;; Atomically claim the next per-poll ordinal, step the pure director under
  ;; the canonical poll attempt, and publish the next director plus evidence.
  ;; The frontend lock is released before this function returns, so any
  ;; delegated poll that may park never holds it. Ordinal claim and director
  ;; transition share one critical section so per-poll ordinals and evidence
  ;; order can never diverge across concurrent callers.
  (locking (:lock frontend)
    (let [state (read-hot-state! frontend)
          ordinal (:next-attempt state)
          attempt {:attempt-id [attempt-id-tag ordinal]
                   :boundary attempt-boundary
                   :operation attempt-operation}
          stepped (fault/step (:director state) attempt)
          evidence (:evidence stepped)
          fired? (some? (:firing evidence))
          captured-result
          (when fired?
            ;; Validate and interpret before publishing the director transition.
            ;; A bad outcome or missing target errno leaves the same attempt and
            ;; rule eligible, so catching the error cannot make a later caller
            ;; silently bypass the invalid plan.
            (interpret-fired-outcome!
             (:world frontend) (get-in evidence [:firing :outcome])))
          canonical-evidence (trace/canonical-value evidence)]
      (reset! (:state frontend)
              {:next-attempt (inc ordinal)
               :initial-director (:initial-director state)
               :director (:director stepped)
               :history (conj (:history state) canonical-evidence)})
      {:ordinal ordinal
       :fired fired?
       :captured-result captured-result})))

(defn- captured-eintr-pair [world]
  ;; EINTR is resolved from the POSIX target descriptor exactly. It is not part
  ;; of the loopback world's required-errnos set, so a target that does not
  ;; carry it cannot satisfy a captured EINTR outcome and fails closed here
  ;; before the POSIX model is invoked or mutated.
  (let [eintr (get-in world [:target :errno :eintr])]
    (when-not (and (integer? eintr) (pos? eintr))
      (fail! missing-target-errno :missing-target-eintr {:errno :eintr}))
    [-1 eintr]))

(defn- interpret-fired-outcome! [world outcome]
  ;; Exactly one frontend-owned outcome shape is accepted: the two-key map
  ;; {:kind :captured-error :errno :eintr}. Any other fired value fails closed
  ;; with a typed error here, before the POSIX model is invoked or mutated.
  (when-not (map? outcome)
    (fail! unsupported-fired-outcome :outcome-must-be-map {:outcome outcome}))
  (let [outcome-keys (set (keys outcome))
        unknown (vec (sort-by pr-str
                              (remove captured-error-outcome-keys
                                      (keys outcome))))
        missing (vec (sort-by pr-str
                              (remove #(contains? outcome-keys %)
                                      captured-error-outcome-keys)))]
    (when (seq unknown)
      (fail! unsupported-fired-outcome :unknown-outcome-keys
             {:outcome outcome :unknown unknown}))
    (when (seq missing)
      (fail! unsupported-fired-outcome :missing-outcome-keys
             {:outcome outcome :missing missing})))
  (let [kind (:kind outcome)
        errno-key (:errno outcome)]
    (cond
      (and (= :captured-error kind) (= :eintr errno-key))
      (captured-eintr-pair world)

      (not= :captured-error kind)
      (fail! unsupported-fired-outcome :unsupported-outcome-kind
             {:outcome outcome :kind kind})

      :else
      (fail! unsupported-fired-outcome :unsupported-captured-errno
             {:outcome outcome :errno errno-key}))))

(defn- poll-wrapper [frontend original-poll]
  (fn [descriptor]
    (let [decision (claim-and-step! frontend)]
      (if (:fired decision)
        (:captured-result decision)
        (original-poll descriptor)))))

(defn- poll-key? [key]
  (and (vector? key)
       (= :foreign-function (nth key 0 nil))
       (= "poll" (nth key 1 nil))))

(defn foreign-handlers
  "Returns the POSIX loopback foreign-handlers map with only the poll handler
  interposed by ``frontend``. The exact handler key set is preserved: every
  non-poll foreign function delegates unchanged to the loopback world, and the
  poll key keeps its target-exact signature while its handler routes through
  the fault director before delegating."
  [frontend]
  (let [frontend (require-frontend! frontend)
        _ (read-state! frontend)
        base (posix/foreign-handlers (:world frontend))]
    (into {}
          (map (fn [[key handler]]
                 (if (poll-key? key)
                   [key (poll-wrapper frontend handler)]
                   [key handler])))
          base)))

(defn handlers
  "Returns one complete runtime :ffi-handlers map for ``frontend``: the
  loopback world's native-memory handlers plus the frontend-interposed foreign
  handlers."
  [frontend]
  (let [frontend (require-frontend! frontend)
        _ (read-state! frontend)
        world (:world frontend)]
    (merge (:memory-handlers world) (foreign-handlers frontend))))

(defn snapshot
  "Stable plain-data summary of the frontend director state: the next per-poll
  ordinal, the global firing count, and per-rule activation and match/firing
  counts. Omits host identities and mutable aliases."
  [frontend]
  (let [state (read-state! frontend)
        director (:director state)]
    {:next-attempt (:next-attempt state)
     :firings (:firings director)
     :rules (mapv (fn [rule]
                    {:rule-id (:id rule)
                     :on-match (:on-match rule)
                     :times (:times rule)
                     :matches (:matches rule)
                     :firings (:firings rule)})
                  (:rules director))}))

(defn evidence-history
  "Returns the exact stable evidence history, one entry per poll attempt in
  per-poll ordinal order. Each entry is the evidence map returned by
  jolt.sim.fault/step for that attempt."
  [frontend]
  (mapv trace/restore-value (:history (read-state! frontend))))

(defn attempts
  "Returns the count of poll attempts observed by ``frontend`` so far."
  [frontend]
  (dec (:next-attempt (read-state! frontend))))
