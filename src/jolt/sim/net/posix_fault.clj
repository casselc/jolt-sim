(ns jolt.sim.net.posix-fault
  "First two boundary frontends for jolt.sim.fault over the POSIX loopback
  world: poll and recv.

  Wraps the plain foreign-handlers map returned by
  jolt.sim.net.posix-loopback/foreign-handlers and interposes the poll handler
  unconditionally, plus the recv handler only when the supplied plan requests
  it. Every poll call atomically steps a pure fault director with a canonical
  {:boundary :posix :operation :poll} attempt whose :attempt-id is the
  frontend-owned per-call ordinal in the form [::poll ordinal]. Every
  interposed recv call steps the same director with a canonical
  {:boundary :posix :operation :recv :peer-port P} attempt, where P is
  derived from the call's fd through the world-owned locked socket-facts seam
  (jolt.sim.net.posix-loopback/socket-facts) -- never from task/thread identity, raw
  pointer identity, arrival order, or a caller-supplied classifier. Its
  :attempt-id is [::recv ordinal fd peer-port]: the fd and resolved peer-port
  are folded into the id itself so a stored evidence history is
  self-describing and can be replayed and validated without re-consulting
  live (and possibly since-mutated or closed) world state. Poll and recv keep
  independent frontend-owned ordinals so adding recv interposition cannot
  renumber the existing poll IDs; their relative cross-operation order is the
  order of entries in the evidence history.

  When no rule fires, the exact original descriptor is delegated once and its
  captured result is returned unchanged. Two frontend-owned outcome shapes are
  accepted at construction, tied to their operation: a poll rule uses outcome
  {:kind :captured-error :errno :eintr}; a recv rule's matcher must be exactly
  {:boundary :posix :operation :recv :peer-port P} with P a positive integer,
  and outcome exactly {:kind :captured-error :errno :econnreset}. Any other
  boundary/operation/outcome combination, or a target missing the exact
  positive target errno a rule's outcome resolves to, is rejected before a
  frontend is returned. When an accepted rule fires, poll returns the
  captured [-1 target-EINTR] pair, and recv returns the captured
  [-1 target-ECONNRESET] pair, without invoking or mutating the modeled poll
  or recv operation respectively.

  The recv handler is interposed only when the validated plan contains at
  least one recv rule; otherwise recv delegates unchanged and never claims an
  attempt ordinal. The poll handler's unconditional interposition (including
  under an empty plan) is preserved exactly as before. Existing catch-all and
  attempt-id-qualified poll matchers remain valid in poll-only plans. In a
  mixed poll+recv plan, each poll rule must explicitly include
  {:boundary :posix :operation :poll}; it may retain additional qualifiers.

  This is not a socket, poll, jolt.net, HTTP, or SQLite implementation. It
  only composes the existing POSIX loopback foreign handler set with the pure
  fault director at the poll and recv seams. Director transitions and
  evidence publication are serialized across concurrent callers on a
  frontend-owned lock; the lock is never held while a delegated poll or recv
  call runs."
  (:require [jolt.sim.fault :as fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.trace :as trace]))

(def invalid-frontend-input :jolt.sim.net.posix-fault/invalid-frontend-input)
(def unsupported-fired-outcome :jolt.sim.net.posix-fault/unsupported-fired-outcome)
(def unsupported-rule-match :jolt.sim.net.posix-fault/unsupported-rule-match)
(def missing-target-errno :jolt.sim.net.posix-fault/missing-target-errno)

(def ^:private world-type :jolt.sim.net.posix-loopback/world)
(def ^:private frontend-type ::frontend)
(def ^:private director-type-key :jolt.sim.fault/type)
(def ^:private director-type :jolt.sim.fault/director)
(def ^:private frontend-keys #{:world :lock :state :type})
(def ^:private state-keys
  #{:next-attempt :next-recv :initial-director :director :history})

;; Canonical attempt identity. Each operation owns a monotonically increasing
;; ordinal. The poll attempt-id form ([::poll ordinal]) and its historical
;; per-poll numbering are unchanged from the poll-only slice.
;; The recv attempt-id form ([::recv ordinal fd peer-port]) additionally
;; folds in the exact fd and resolved peer-port used to match that call, so
;; replaying stored evidence never needs to re-derive resource identity from
;; live (and possibly since-mutated) world state.
(def ^:private attempt-boundary :posix)
(def ^:private poll-operation :poll)
(def ^:private recv-operation :recv)
(def ^:private poll-attempt-id-tag :jolt.sim.net.posix-fault/poll)
(def ^:private recv-attempt-id-tag :jolt.sim.net.posix-fault/recv)

;; The two frontend-owned outcome shapes this slice interprets, keyed by the
;; operation whose rule they are attached to.
(def ^:private captured-error-outcome-keys #{:kind :errno})
(def ^:private recv-match-keys #{:boundary :operation :peer-port})

(defn- fail! [type reason data]
  (throw
   (ex-info (str "jolt.sim.net.posix-fault " (name reason))
            (assoc data :type type :reason reason))))

(defn- positive-int? [value]
  (and (integer? value) (pos? value)))

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

(defn- restore-match [rule]
  ;; A compiled rule's :match maps plain keys to canonical-form values (see
  ;; jolt.sim.fault/freeze-matcher). Restoring every value yields an ordinary
  ;; comparable map for closed-shape validation.
  (into {}
        (map (fn [[key canon-val]] [key (trace/restore-value canon-val)]))
        (:match rule)))

(defn- rule-operation! [rule]
  ;; Existing poll frontends accepted catch-all and attempt-id-qualified
  ;; matchers, so retain that surface. A rule explicitly naming :recv is new
  ;; and therefore must use the exact closed resource matcher. Explicitly
  ;; unsupported operations fail closed before a frontend is returned.
  (let [match (restore-match rule)
        match-keys (set (keys match))
        operation (:operation match)]
    (cond
      (= poll-operation operation)
      poll-operation

      (= recv-operation operation)
      (if (and (= recv-match-keys match-keys)
               (= attempt-boundary (:boundary match))
               (positive-int? (:peer-port match)))
        recv-operation
        (fail! unsupported-rule-match :unsupported-rule-match
               {:rule-id (:id rule) :match match}))

      (contains? match :operation)
      (fail! unsupported-rule-match :unsupported-rule-match
             {:rule-id (:id rule) :match match})

      :else
      poll-operation)))

(defn- director-requests-operation? [director operation]
  (boolean (some #(= operation (rule-operation! %)) (:rules director))))

(defn- validate-director-outcomes! [world director]
  ;; This POSIX frontend has one closed outcome algebra per operation.
  ;; Validate every rule's matcher shape and outcome before admitting a
  ;; frontend, not only when a rule becomes eligible, so a valid frontend can
  ;; publish one ordinal/evidence entry for every interposed call.
  (let [classified
        (mapv (fn [rule] [rule (rule-operation! rule)]) (:rules director))
        recv? (boolean (some #(= recv-operation (second %)) classified))]
    ;; A legacy poll matcher without an explicit :operation was safe when the
    ;; frontend stepped only poll. In a mixed plan it would also match recv and
    ;; could fire a poll-only outcome at the wrong operation. Keep every such
    ;; poll-only plan valid, but reject the newly ambiguous mixed form.
    (when recv?
      (doseq [[rule operation] classified]
        (when (and (= poll-operation operation)
                   (not= poll-operation (:operation (restore-match rule))))
          (fail! unsupported-rule-match :ambiguous-poll-matcher
                 {:rule-id (:id rule) :match (restore-match rule)}))))
    (doseq [[rule operation] classified]
      (interpret-fired-outcome!
       world operation (trace/restore-value (:outcome rule)))))
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
  ;; handler registry or target-specific signature can be manufactured.
  (posix/validate-world (:world value))
  value)

(defn- attempt-for-next-ordinals
  "Reconstructs the exact attempt that must have produced ``restored-entry``
  from the entry's own self-describing :attempt-id and the next expected
  per-operation ordinals, without consulting live world state. Returns nil
  for any unrecognized shape."
  [next-poll next-recv restored-entry]
  (let [attempt-id (:attempt-id restored-entry)
        tag (when (vector? attempt-id) (nth attempt-id 0 nil))]
    (cond
      (and (= poll-attempt-id-tag tag)
           (= 2 (count attempt-id))
           (= next-poll (nth attempt-id 1 nil)))
      {:operation poll-operation
       :attempt {:attempt-id attempt-id
                 :boundary attempt-boundary
                 :operation poll-operation}}

      (and (= recv-attempt-id-tag tag)
           (= 4 (count attempt-id))
           (= next-recv (nth attempt-id 1 nil)))
      {:operation recv-operation
       :attempt {:attempt-id attempt-id
                 :boundary attempt-boundary
                 :operation recv-operation
                 :peer-port (nth attempt-id 3 nil)}}

      :else nil)))

(defn- replay-history! [initial-director history]
  ;; Evidence is retained canonically. Replaying every frontend attempt shape
  ;; recorded in history proves exact evidence shape, sequential per-operation
  ;; attempt IDs,
  ;; and coherence between the initial director, history, and current
  ;; counters. Each entry's own attempt-id carries all resource identity
  ;; needed to rebuild its attempt, so replay never re-derives peer-port from
  ;; (possibly since-mutated) live world state.
  (loop [history-ordinal 1
         next-poll 1
         next-recv 1
         director initial-director
         remaining history]
    (if (seq remaining)
      (let [restored (trace/restore-value (first remaining))
            replay (attempt-for-next-ordinals next-poll next-recv restored)]
        (when (nil? replay)
          (fail! invalid-frontend-input :invalid-frontend-history
                 {:attempt-ordinal history-ordinal}))
        (let [operation (:operation replay)
              stepped (fault/step director (:attempt replay))
              expected (trace/canonical-value (:evidence stepped))]
          (when-not (= expected (first remaining))
            (fail! invalid-frontend-input :invalid-frontend-history
                   {:attempt-ordinal history-ordinal}))
          (recur (inc history-ordinal)
                 (if (= poll-operation operation) (inc next-poll) next-poll)
                 (if (= recv-operation operation) (inc next-recv) next-recv)
                 (:director stepped)
                 (next remaining))))
      {:director director
       :next-attempt next-poll
       :next-recv next-recv})))

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
                   (integer? (:next-recv state))
                   (pos? (:next-recv state))
                   (vector? (:history state))
                   (= (+ (dec (:next-attempt state))
                         (dec (:next-recv state)))
                      (count (:history state)))
                   (every? trace/canonical-form? (:history state)))
      (fail! invalid-frontend-input :invalid-frontend-state {}))
    (let [initial (fault/validate-director (:initial-director state))
          director (fault/validate-director (:director state))]
      (validate-director-outcomes! (:world frontend) initial)
      (validate-director-outcomes! (:world frontend) director)
      (when-not (= {:director director
                    :next-attempt (:next-attempt state)
                    :next-recv (:next-recv state)}
                   (replay-history! initial (:history state)))
        (fail! invalid-frontend-input :incoherent-frontend-state {})))
    state))

(defn- read-hot-state! [frontend]
  ;; Handler maps are created only after read-state! has validated the complete
  ;; frontend, world, and replay history. The wrapper then owns the only normal
  ;; writes to this state atom. Keep the per-call check bounded to the closed
  ;; state shape plus the current director/outcome contracts; replaying the
  ;; complete history or rebuilding world-handler evidence here would make N
  ;; calls quadratic and can consume an application's real monotonic deadline.
  (let [state
        (try
          @(:state frontend)
          (catch :default _
            (fail! invalid-frontend-input :invalid-frontend-state {})))]
    (when-not (and (map? state)
                   (= state-keys (set (keys state)))
                   (integer? (:next-attempt state))
                   (pos? (:next-attempt state))
                   (integer? (:next-recv state))
                   (pos? (:next-recv state))
                   (vector? (:history state))
                   (= (+ (dec (:next-attempt state))
                         (dec (:next-recv state)))
                      (count (:history state))))
      (fail! invalid-frontend-input :invalid-frontend-state {}))
    (fault/validate-director (:initial-director state))
    (validate-director-outcomes!
     (:world frontend) (fault/validate-director (:director state)))
    state))

(defn frontend
  "Returns a POSIX poll+recv fault frontend wrapping the loopback ``world``
  and a closed fault ``plan-or-director``.

  ``plan-or-director`` is either a vector plan accepted by
  jolt.sim.fault/director or an already-built, fully validated director map.
  Every rule's outcome and operation must satisfy one of two contracts:

  * A poll matcher, paired with outcome {:kind :captured-error :errno :eintr};
    the supplied POSIX world must expose an exact positive target EINTR value.
    Existing catch-all and attempt-id-qualified forms remain valid in
    poll-only plans. In a mixed plan, poll matchers must explicitly include
    {:boundary :posix :operation :poll}, with optional additional qualifiers.
  * {:boundary :posix :operation :recv :peer-port P} with P a positive
    integer, paired with outcome {:kind :captured-error :errno :econnreset};
    the supplied POSIX world must expose an exact positive target ECONNRESET
    value.

  Any other boundary/operation/outcome combination, or a target missing the
  exact positive value a rule's outcome resolves to, is rejected before the
  frontend is returned.

  The frontend owns deterministic per-operation attempt ordinals (starting at
  1), the next director state, and an exact stable evidence history. Poll keeps
  its historical per-poll numbering; cross-operation order is the history
  order. A pre-built director is validated before the frontend is
  returned, so snapshots cannot expose forged state before the first call.

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
                   :next-recv 1
                   :initial-director director
                   :director director
                   :history []})
     :type frontend-type}))

(defn- claim-and-step! [frontend operation attempt-fn]
  ;; Atomically claim the next per-call ordinal, step the pure director under
  ;; the canonical attempt built by attempt-fn, and publish the next director
  ;; plus evidence. The frontend lock is released before this function
  ;; returns, so any delegated call that may park (poll) never holds it.
  ;; Ordinal claim and director transition share one critical section so
  ;; per-call ordinals and evidence order can never diverge across concurrent
  ;; callers, regardless of which interposed operation they invoke.
  (locking (:lock frontend)
    (let [state (read-hot-state! frontend)
          counter-key (if (= recv-operation operation)
                        :next-recv
                        :next-attempt)
          ordinal (get state counter-key)
          attempt (attempt-fn ordinal)
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
             (:world frontend) operation (get-in evidence [:firing :outcome])))
          canonical-evidence (trace/canonical-value evidence)]
      (reset! (:state frontend)
              (assoc state
                     counter-key (inc ordinal)
                     :director (:director stepped)
                     :history (conj (:history state) canonical-evidence)))
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

(defn- captured-econnreset-pair [world]
  ;; ECONNRESET is part of the loopback world's required-errnos set, so an
  ;; ordinarily constructed world always carries a value here. This check is
  ;; still exact and fail-closed: posix-loopback only requires an integer, not
  ;; a positive one, so a target carrying a non-positive ECONNRESET still
  ;; cannot satisfy a captured ECONNRESET outcome.
  (let [econnreset (get-in world [:target :errno :econnreset])]
    (when-not (and (integer? econnreset) (pos? econnreset))
      (fail! missing-target-errno :missing-target-econnreset
             {:errno :econnreset}))
    [-1 econnreset]))

(defn- interpret-fired-outcome! [world operation outcome]
  ;; Exactly one frontend-owned outcome shape is accepted per operation: a
  ;; poll rule fires {:kind :captured-error :errno :eintr}; a recv rule fires
  ;; {:kind :captured-error :errno :econnreset}. Any other fired value, or a
  ;; value paired with the wrong operation, fails closed with a typed error
  ;; here, before the POSIX model is invoked or mutated.
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
      (not= :captured-error kind)
      (fail! unsupported-fired-outcome :unsupported-outcome-kind
             {:outcome outcome :kind kind})

      (and (= poll-operation operation) (= :eintr errno-key))
      (captured-eintr-pair world)

      (and (= recv-operation operation) (= :econnreset errno-key))
      (captured-econnreset-pair world)

      :else
      (fail! unsupported-fired-outcome :unsupported-captured-errno
             {:outcome outcome :errno errno-key :operation operation}))))

(defn- poll-attempt [ordinal]
  {:attempt-id [poll-attempt-id-tag ordinal]
   :boundary attempt-boundary
   :operation poll-operation})

(defn- poll-wrapper [frontend original-poll]
  (fn [descriptor]
    (let [decision (claim-and-step! frontend poll-operation poll-attempt)]
      (if (:fired decision)
        (:captured-result decision)
        (original-poll descriptor)))))

(defn- resolve-peer-port
  "Derives the modeled :peer-port for ``fd`` from the world-owned locked
  posix-loopback socket-facts snapshot, never from task/thread identity, raw
  pointer identity, arrival order, or a caller-supplied classifier. The locked
  snapshot is the boundary-admission linearization point. Returns nil when
  ``fd`` is not a currently modeled connected socket, which cannot match any
  recv rule's required positive-integer :peer-port."
  [world fd]
  (:peer-port (posix/socket-facts world fd)))

(defn- recv-attempt [ordinal fd peer-port]
  {:attempt-id [recv-attempt-id-tag ordinal fd peer-port]
   :boundary attempt-boundary
   :operation recv-operation
   :peer-port peer-port})

(defn- recv-wrapper [frontend original-recv]
  (fn [descriptor]
    (let [fd (nth (:arguments descriptor) 0 nil)
          peer-port (resolve-peer-port (:world frontend) fd)
          decision (claim-and-step!
                    frontend recv-operation
                    (fn [ordinal] (recv-attempt ordinal fd peer-port)))]
      (if (:fired decision)
        (:captured-result decision)
        (original-recv descriptor)))))

(defn- poll-key? [key]
  (and (vector? key)
       (= :foreign-function (nth key 0 nil))
       (= "poll" (nth key 1 nil))))

(defn- recv-key? [key]
  (and (vector? key)
       (= :foreign-function (nth key 0 nil))
       (= "recv" (nth key 1 nil))))

(defn foreign-handlers
  "Returns the POSIX loopback foreign-handlers map with the poll handler
  always interposed by ``frontend``, and the recv handler interposed only
  when ``frontend``'s validated plan contains at least one recv rule. The
  exact handler key set is preserved: every non-interposed foreign function
  delegates unchanged to the loopback world, and every interposed key keeps
  its target-exact signature while its handler routes through the fault
  director before delegating."
  [frontend]
  (let [frontend (require-frontend! frontend)
        state (read-state! frontend)
        wrap-recv? (director-requests-operation?
                    (:director state) recv-operation)
        base (posix/foreign-handlers (:world frontend))]
    (into {}
          (map (fn [[key handler]]
                 (cond
                   (poll-key? key)
                   [key (poll-wrapper frontend handler)]

                   (and wrap-recv? (recv-key? key))
                   [key (recv-wrapper frontend handler)]

                   :else
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
  "Stable plain-data summary of the frontend director state: the historically
  compatible next poll-attempt ordinal, the global firing count, and per-rule
  activation and match/firing counts. Omits host identities and mutable
  aliases."
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
  "Returns the exact stable evidence history, one entry per interposed attempt
  in cross-operation admission order. Poll and recv IDs keep independent
  per-operation ordinals. Each entry is the evidence map returned by
  jolt.sim.fault/step for that attempt."
  [frontend]
  (mapv trace/restore-value (:history (read-state! frontend))))

(defn attempts
  "Returns the count of poll attempts observed by ``frontend`` so far,
  preserving the poll-only frontend's public contract. Recv attempts remain
  available in [[evidence-history]]."
  [frontend]
  (dec (:next-attempt (read-state! frontend))))
