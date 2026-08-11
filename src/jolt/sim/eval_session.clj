(ns jolt.sim.eval-session
  "Persistent, UI-neutral evaluation sessions over jolt.sim.eval-stream.

  An EvalSession owns one coherent REPL thread context: its namespace and
  *1/*2/*3/*e history, a bounded vector of complete evaluation envelopes, and
  one serialization lock.  It deliberately owns no socket, browser protocol,
  interruption mechanism, durable storage, or simulation command surface.

  Callers submit exactly {:form string}.  The session delegates each accepted
  request exactly once to eval-stream/evaluate! with :history :thread, retains
  the resulting raw structured events as one indivisible evaluation, commits
  namespace/history state, and only then publishes the envelope through tap>.
  A declined or failed observational tap never changes the evaluation result."
  (:require [clojure.core.protocols :as protocols]
            [jolt.sim.eval-stream :as eval-stream]))

(declare evaluate-state!)

(def ^:private default-retained-evaluations 32)
(def ^:private maximum-retained-evaluations 4096)

;; EvalSession is an opaque closure-backed capability.  The unforgeable
;; operation objects prevent callers from reaching its atom or lock.
(def ^:private snapshot-operation (Object.))
(def ^:private recent-operation (Object.))
(def ^:private evaluate-operation (Object.))
(def ^:private close-operation (Object.))

(defn- session-error [reason data]
  (ex-info "jolt-sim evaluation session rejected an operation"
           (merge {:type :jolt.sim.eval-session/rejected
                   :reason reason}
                  data)))

(defn- validate-config! [config]
  (when-not (map? config)
    (throw (session-error :config-not-a-map
                          {:value-class (str (class config))})))
  (when-not (every? #{:allow-unresolved-vars? :retained-evaluations}
                    (keys config))
    (throw (session-error :unknown-config-key
                          {:key-count (count config)})))
  (let [allow? (get config :allow-unresolved-vars? false)
        retained (get config :retained-evaluations
                      default-retained-evaluations)]
    (when-not (boolean? allow?)
      (throw (session-error :allow-unresolved-vars-not-boolean
                            {:value-class (str (class allow?))})))
    (when-not (and (integer? retained)
                   (<= 1 retained maximum-retained-evaluations))
      (throw (session-error :retained-evaluations-out-of-range
                            (cond->
                             {:minimum 1
                              :maximum maximum-retained-evaluations
                              :value-class (str (class retained))}
                              (integer? retained) (assoc :value retained)))))
    {:allow-unresolved-vars? allow?
     :retained-evaluations retained}))

(defn- validate-request! [request]
  (when-not (map? request)
    (throw (session-error :request-not-a-map
                          {:value-class (str (class request))})))
  (when-not (= #{:form} (set (keys request)))
    (throw (session-error :invalid-request-shape
                          {:key-count (count request)})))
  (when-not (string? (:form request))
    (throw (session-error :form-not-a-string
                          {:value-class (str (class (:form request)))})))
  {:form (:form request)})

(defn- evaluation-token [evaluations]
  (if (empty? evaluations)
    {:from nil :through nil :count 0}
    {:from (:sequence (first evaluations))
     :through (:sequence (peek evaluations))
     :count (count evaluations)}))

(defn- snapshot-state [state]
  (let [{:keys [closed? namespace next-sequence evaluations
                retained-evaluations]} @state]
    {:kind :jolt.sim.kind/eval-session
     :status (if closed? :closed :open)
     :namespace (str (ns-name namespace))
     :next-sequence next-sequence
     :retained-evaluations retained-evaluations
     :evaluations (evaluation-token evaluations)}))

(defn snapshot
  "Returns the session's cheap immutable summary.

  Complete retained evaluations are represented by the :evaluations token.
  Use recent, or clojure.datafy/nav on the datafied session, to retrieve them."
  [session]
  (session snapshot-operation nil))

(defn recent
  "Returns the bounded vector of complete retained evaluation envelopes."
  [session]
  (session recent-operation nil))

(defn- valid-token? [token]
  (and (map? token)
       (= #{:from :through :count} (set (keys token)))
       (integer? (:count token))
       (not (neg? (:count token)))
       (if (zero? (:count token))
         (and (nil? (:from token)) (nil? (:through token)))
         (and (integer? (:from token))
              (not (neg? (:from token)))
              (integer? (:through token))
              (<= (:from token) (:through token))
              (= (:count token)
                 (inc (- (:through token) (:from token))))))))

(defn- navigate-evaluations [state lock token]
  (when-not (valid-token? token)
    (throw (session-error :invalid-navigation
                          {:value-class (str (class token))
                           :key-count (when (map? token) (count token))})))
  (locking lock
    (if (zero? (:count token))
      []
      (let [captured
            (->> (:evaluations @state)
                 (filter #(<= (:from token)
                              (:sequence %)
                              (:through token)))
                 vec)]
        (when-not (and (= (:count token) (count captured))
                       (= (:from token) (:sequence (first captured)))
                       (= (:through token) (:sequence (peek captured))))
          (throw (session-error :stale-navigation
                                {:from (:from token)
                                 :through (:through token)})))
        captured))))

(defn- trim-evaluations [evaluations limit]
  (let [overflow (- (count evaluations) limit)]
    (if (pos? overflow)
      (subvec evaluations overflow)
      evaluations)))

(defn- publish-tap! [envelope]
  ;; tap> is observational.  Queue rejection and even an unexpected tap
  ;; implementation failure occur after commit and cannot change evaluation.
  (try
    (tap> envelope)
    (catch :default _ nil))
  nil)

(defn evaluate!
  "Evaluates one exact {:form string} request in the persistent session.

  Calls eval-stream/evaluate! exactly once with the session-owned namespace,
  unresolved-var policy, and :history :thread.  Returns and retains one
  envelope containing the raw ordered eval-stream events.  Concurrent calls
  serialize; a closed session rejects before invoking eval-stream."
  [session request]
  (session evaluate-operation (validate-request! request)))

(defn close!
  "Closes the session idempotently and returns its current summary.

  Retained evaluations remain inspectable; subsequent evaluate! calls fail
  with typed :jolt.sim.eval-session/rejected ex-data and reason :closed."
  [session]
  (session close-operation nil))

(defn- evaluate-state! [state lock request]
  (locking lock
    (let [{:keys [closed? namespace one two three error
                  allow-unresolved-vars? retained-evaluations
                  next-sequence] :as before} @state]
      (when closed?
        (throw (session-error :closed {})))
      (binding [*1 one
                *2 two
                *3 three
                *e error]
        (let [events (atom [])
              form (:form request)
              ns-before (str (ns-name namespace))
              terminal
              (eval-stream/evaluate!
               {:form form
                :ns ns-before
                :history :thread
                :allow-unresolved-vars? allow-unresolved-vars?}
               #(swap! events conj %))
              namespace-after (the-ns (symbol (:ns terminal)))
              envelope {:kind :jolt.sim.kind/evaluation
                        :sequence next-sequence
                        :request request
                        :namespace {:before ns-before
                                    :after (:ns terminal)}
                        :events @events}
              retained (trim-evaluations
                        (conj (:evaluations before) envelope)
                        retained-evaluations)]
          (reset! state
                  (assoc before
                         ;; The terminal event is the evaluator's public
                         ;; namespace report; do not infer it from source text.
                         :namespace namespace-after
                         :one *1
                         :two *2
                         :three *3
                         :error *e
                         :next-sequence (inc next-sequence)
                         :evaluations retained))
          (publish-tap! envelope)
          envelope)))))

(defn start
  "Starts a persistent evaluation session in the user namespace.

  Options are a closed map:
  - :allow-unresolved-vars? boolean, default false;
  - :retained-evaluations integer 1..4096, default 32.

  The returned value is an opaque datafy/nav capability, not its state map."
  ([] (start {}))
  ([config]
   (let [{:keys [allow-unresolved-vars? retained-evaluations]}
         (validate-config! config)
         state (atom {:closed? false
                      :namespace (the-ns 'user)
                      :one nil
                      :two nil
                      :three nil
                      :error nil
                      :allow-unresolved-vars? allow-unresolved-vars?
                      :retained-evaluations retained-evaluations
                      :next-sequence 0
                      :evaluations []})
         lock (Object.)]
     (reify
       clojure.lang.IFn
       (invoke [_ operation argument]
         (cond
           (identical? operation snapshot-operation)
           (snapshot-state state)

           (identical? operation recent-operation)
           (:evaluations @state)

           (identical? operation evaluate-operation)
           (evaluate-state! state lock argument)

           (identical? operation close-operation)
           (locking lock
             (when-not (:closed? @state)
               (swap! state assoc :closed? true))
             (snapshot-state state))

           :else
           (throw (session-error :invalid-operation {}))))

       protocols/Datafiable
       (datafy [this]
         (snapshot this))

       protocols/Navigable
       (nav [_ key value]
         (if (= :evaluations key)
           (navigate-evaluations state lock value)
           value))))))
