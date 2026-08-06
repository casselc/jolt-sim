(ns jolt.sim.session
  "REPL-first, UI-neutral control over the cooperative kernel machine.

  A Session is a small mutable cursor over the kernel's immutable explicit
  state. It does not implement another scheduler: every enabled branch and
  transition delegates to kernel/machine-actions and kernel/machine-apply.

  The wrapped simulation must obey the kernel machine contract: its step
  function is deterministic and effect-free outside the value-semantic task
  and world state. Branch preview evaluates sibling transitions, so host I/O,
  clocks, entropy, or closed-over mutation would make previews unsound."
  (:require [clojure.core.protocols :as protocols]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.trace :as trace]))

(declare snapshot branches journal step-state!)

;; A Session is an opaque closure-backed capability. Jolt currently exposes
;; deftype fields through keyword lookup and keeps generated constructors and
;; private protocol functions in ns-publics. Private unforgeable operation
;; tokens let public functions invoke the closure without returning its atom or
;; lock through any supported API.
(def ^:private snapshot-operation (Object.))
(def ^:private branches-operation (Object.))
(def ^:private navigate-branches-operation (Object.))
(def ^:private journal-operation (Object.))
(def ^:private step-operation (Object.))

(defn- appended-events [before after]
  (let [before-count (count (kernel/machine-trace before))]
    (subvec (kernel/machine-trace after) before-count)))

(defn- transition-site [events]
  (some trace/transition-site events))

(defn- branch-item [revision machine action]
  (let [child (kernel/machine-apply machine action)
        events (appended-events machine child)]
    {:machine child
     :preview
     {:branch {:revision revision :action action}
      :site (transition-site events)
      :status (kernel/machine-status child)
      :projection (kernel/machine-projection child)
      :events events}}))

(defn- branch-ref [revision action]
  {:revision revision :action action})

(defn- summary* [{:keys [machine revision journal]}]
  {:revision revision
   :status (kernel/machine-status machine)
   :projection (kernel/machine-projection machine)
   ;; Keep datafy cheap and side-effect-free with respect to the step callback.
   ;; Full successor projections are available through `branches` or `nav`.
   :branches (mapv #(branch-ref revision %)
                   (kernel/machine-actions machine))
   :journal {:count (count journal)}})

(defn- snapshot-state [state]
  (summary* @state))

(defn snapshot
  "Returns an immutable, canonicalizable summary of the current session.

  `:branches` contains only enabled action identities. Call `branches`, or use
  `clojure.datafy/nav` with the original Session retained as
  `:clojure.datafy/obj` metadata, to evaluate isolated successor previews."
  [session]
  (session snapshot-operation nil))

(defn actions
  "Returns the current stable revision-scoped branch references without
  evaluating any step callback."
  [session]
  (:branches (snapshot session)))

(defn- branches-state-locked [state]
  (let [{:keys [machine revision branch-cache] :as before} @state]
    (if (= revision (:revision branch-cache))
      (mapv :preview (:items branch-cache))
      (let [items (mapv #(branch-item revision machine %)
                        (kernel/machine-actions machine))]
        (reset! state
                (assoc before :branch-cache
                       {:revision revision :items items}))
        (mapv :preview items)))))

(defn- branches-state [state lock]
  (locking lock
    (branches-state-locked state)))

(defn- navigate-branches-state [state lock branch-values]
  (locking lock
    (let [current (snapshot-state state)
          current-revision (:revision current)]
      (when-not
       (and (vector? branch-values)
            (every?
             (fn [branch]
               (and (map? branch)
                    (= #{:revision :action} (set (keys branch)))
                    (integer? (:revision branch))
                    (not (neg? (:revision branch)))
                    (vector? (:action branch))
                    (= 2 (count (:action branch)))))
             branch-values)
            (or (empty? branch-values)
                (apply = (map :revision branch-values))))
        (throw
         (ex-info "Session branch navigation is malformed"
                  {:type ::invalid-navigation :value branch-values})))
      (let [captured-revision (:revision (first branch-values))]
        (when (and (some? captured-revision)
                   (not= captured-revision current-revision))
          (throw
           (ex-info "Session branch navigation is stale"
                    {:type ::stale-navigation
                     :captured-revision captured-revision
                     :current-revision current-revision})))
        (when-not (= branch-values (:branches current))
          (throw
           (ex-info "Session branch navigation does not match its revision"
                    {:type ::invalid-navigation :value branch-values})))
        (branches-state-locked state)))))

(defn branches
  "Returns deterministic previews for every currently enabled kernel action.

  A preview contains the action, resulting status/projection, newly appended
  trace events, and the transition's caller-defined site when one exists. The
  live session is not advanced."
  [session]
  (session branches-operation nil))

(defn journal
  "Returns the session's immutable in-memory command journal.

  This v1 journal is an ordered semantic record, not yet a crash-durable file.
  A later storage adapter will persist the same entries before acknowledging
  commands."
  [session]
  (session journal-operation nil))

(defn- tap-event! [event]
  ;; tap> is deliberately observational: its bounded asynchronous queue may
  ;; decline an event and can never participate in scheduler control.
  (tap> event)
  nil)

(defn start
  "Starts an interactive Session over one cooperative kernel config.

  Returns a cursor at the initial machine state and emits one observational
  tap event. No task is run until `step!` is called."
  [sim]
  (let [machine (kernel/machine sim)
        initial {:seq 0
                 :command :start
                 :status (kernel/machine-status machine)
                 :projection (kernel/machine-projection machine)}
        _initial-valid (trace/canonical-value initial [:session :journal 0])
        notification {:event :jolt.sim.session/started
                      :revision 0
                      :status (:status initial)}
        _notification-valid (trace/canonical-value notification [:session :tap 0])
        state (atom {:machine machine
                     :revision 0
                     :path []
                     :branch-cache nil
                     :journal [initial]})
        lock (Object.)
        session
        (reify
          clojure.lang.IFn
          (invoke [_ operation argument]
            (cond
              (identical? operation snapshot-operation)
              (snapshot-state state)

              (identical? operation branches-operation)
              (branches-state state lock)

              (identical? operation navigate-branches-operation)
              (navigate-branches-state state lock argument)

              (identical? operation journal-operation)
              (:journal @state)

              (identical? operation step-operation)
              (step-state! state lock argument)

              :else
              (throw
               (ex-info "Unknown Session capability operation"
                        {:type ::invalid-operation}))))

          protocols/Datafiable
          (datafy [this]
            (snapshot this))

          protocols/Navigable
          (nav [this key value]
            (case key
              :branches
              (this navigate-branches-operation value)

              :journal
              (let [captured-count (:count value)
                    entries (journal this)]
                (when-not (and (map? value)
                               (= #{:count} (set (keys value)))
                               (integer? captured-count)
                               (not (neg? captured-count))
                               (<= captured-count (count entries)))
                  (throw
                   (ex-info "Session journal navigation is malformed"
                            {:type ::invalid-navigation :value value})))
                (subvec (vec entries) 0 captured-count))

              value)))]
    (tap-event! notification)
    session))

(defn- plain-branch [branch]
  (when-not (and (map? branch)
                 (= #{:revision :action} (set (keys branch))))
    (throw
     (ex-info "Session step requires an exact revision-scoped branch"
              {:type ::invalid-branch})))
  (let [revision (:revision branch)
        action (:action branch)]
    (when-not (and (integer? revision) (not (neg? revision)))
      (throw
       (ex-info "Session branch revision must be a non-negative integer"
                {:type ::invalid-branch :reason :revision})))
    (when-not (and (vector? action) (= 2 (count action)))
      (throw
       (ex-info "Session branch action must be a two-element vector"
                {:type ::invalid-branch :reason :action})))
    ;; Reconstruct plain data so record identity and metadata cannot enter the
    ;; command path, journal, taps, or future durable adapters.
    (let [plain {:revision revision
                 :action [(nth action 0) (nth action 1)]}]
      (trace/canonical-value plain [:session :branch])
      plain)))

(defn- step-state!
  "Atomically applies one exact revision-scoped branch and returns the new
  snapshot.

  Concurrent callers cannot lose or duplicate a command: the per-session
  command lock serializes revision validation, transition evaluation, commit,
  and tap notification. The committed command is appended once to the in-memory
  journal and tapped only after the state transition commits. A stale revision
  fails closed before applying any callback; invalid, disabled, or terminal
  actions fail through kernel/machine-apply without changing the session."
  [state lock supplied-branch]
  (locking lock
    (let [branch (plain-branch supplied-branch)
          before @state
          machine (:machine before)
          expected-revision (:revision branch)
          action (:action branch)
          actual-revision (:revision before)]
      (when-not (= expected-revision actual-revision)
        (throw
         (ex-info "Session branch is stale"
                  {:type ::stale-branch
                   :expected-revision expected-revision
                   :actual-revision actual-revision
                   :branch branch})))
      (let [cached
            (when (= actual-revision
                     (get-in before [:branch-cache :revision]))
              (some (fn [item]
                      (when (= branch (get-in item [:preview :branch]))
                        item))
                    (get-in before [:branch-cache :items])))
            child (if cached
                    (:machine cached)
                    (kernel/machine-apply machine action))
            revision (inc actual-revision)
            events (appended-events machine child)
            entry {:seq revision
                   :command :step
                   :branch branch
                   :site (transition-site events)
                   :events events
                   :status (kernel/machine-status child)}
            _entry-valid (trace/canonical-value entry
                                                [:session :journal revision])
            notification {:event :jolt.sim.session/stepped
                          :revision revision
                          :branch branch
                          :site (:site entry)
                          :status (:status entry)}
            _notification-valid (trace/canonical-value
                                 notification [:session :tap revision])
            after (-> before
                      (assoc :machine child
                             :revision revision
                             :branch-cache nil)
                      (update :path conj action)
                      (update :journal conj entry))]
        (reset! state after)
        ;; Keep the tap enqueue inside the per-session command lock so commit
        ;; order and notification order cannot diverge.
        (tap-event! notification)
        (summary* after)))))

(defn step!
  "Atomically applies one exact revision-scoped branch and returns the new
  snapshot. See the Session capability contract in this namespace."
  [session supplied-branch]
  (session step-operation supplied-branch))
