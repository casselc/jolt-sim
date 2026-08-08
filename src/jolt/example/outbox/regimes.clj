(ns jolt.example.outbox.regimes
  "Finite, application-owned regimes for the Outbox close/reopen experiment.

  A regime selects only two coordinates already enforced by
  `exercise-reopen-with-capacities`: which reactor's first poll is admitted
  first, and whether one modeled poll call captures EINTR at a supported
  attempt ordinal. It does not claim to order poll completion or arbitrary
  application interleavings.

  The catalog is ordinary immutable data. Ripple, a REPL, or another UI can
  expose its IDs and descriptions while retaining the coordinates on the
  trusted side of the execution boundary."
  (:require [jolt.sim.trace :as trace]))

(def admission-plans
  "The two supported first-poll admission orders."
  [:receiver-poll-then-http-poll
   :http-poll-then-receiver-poll])

(def poll-eintr-ordinals
  "Supported modeled poll-EINTR activation ordinals. nil installs no fault."
  [nil 1 2 4 8])

(def lab-base-input
  "The fixed payload and conservative capacities used by the interactive lab."
  {:payload [0 127 128 255]
   :stream-capacity 8
   :pipe-capacity 1})

(def ^:private regime-scope
  [:jolt.example.outbox/first-poll-admission
   :jolt.example.outbox/modeled-poll-eintr])

(def regimes
  "The complete 2 x 5 finite regime catalog.

  Every descriptor is inert exact data. :coordinates is deliberately limited
  to the two scenario coordinates selected by this lab."
  [{:id :jolt.example.outbox.regime/receiver-first-no-eintr
    :label "Receiver poll first; no EINTR"
    :summary (str "Admit the receiver reactor's first poll before the HTTP "
                  "reactor's first poll; capture no modeled poll EINTR.")
    :scope regime-scope
    :coordinates
    {:admission-plan :receiver-poll-then-http-poll
     :poll-eintr-ordinal nil}}
   {:id :jolt.example.outbox.regime/receiver-first-poll-eintr-1
    :label "Receiver poll first; EINTR at poll 1"
    :summary (str "Admit the receiver reactor's first poll before the HTTP "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 1.")
    :scope regime-scope
    :coordinates
    {:admission-plan :receiver-poll-then-http-poll
     :poll-eintr-ordinal 1}}
   {:id :jolt.example.outbox.regime/receiver-first-poll-eintr-2
    :label "Receiver poll first; EINTR at poll 2"
    :summary (str "Admit the receiver reactor's first poll before the HTTP "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 2.")
    :scope regime-scope
    :coordinates
    {:admission-plan :receiver-poll-then-http-poll
     :poll-eintr-ordinal 2}}
   {:id :jolt.example.outbox.regime/receiver-first-poll-eintr-4
    :label "Receiver poll first; EINTR at poll 4"
    :summary (str "Admit the receiver reactor's first poll before the HTTP "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 4.")
    :scope regime-scope
    :coordinates
    {:admission-plan :receiver-poll-then-http-poll
     :poll-eintr-ordinal 4}}
   {:id :jolt.example.outbox.regime/receiver-first-poll-eintr-8
    :label "Receiver poll first; EINTR at poll 8"
    :summary (str "Admit the receiver reactor's first poll before the HTTP "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 8.")
    :scope regime-scope
    :coordinates
    {:admission-plan :receiver-poll-then-http-poll
     :poll-eintr-ordinal 8}}
   {:id :jolt.example.outbox.regime/http-first-no-eintr
    :label "HTTP poll first; no EINTR"
    :summary (str "Admit the HTTP reactor's first poll before the receiver "
                  "reactor's first poll; capture no modeled poll EINTR.")
    :scope regime-scope
    :coordinates
    {:admission-plan :http-poll-then-receiver-poll
     :poll-eintr-ordinal nil}}
   {:id :jolt.example.outbox.regime/http-first-poll-eintr-1
    :label "HTTP poll first; EINTR at poll 1"
    :summary (str "Admit the HTTP reactor's first poll before the receiver "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 1.")
    :scope regime-scope
    :coordinates
    {:admission-plan :http-poll-then-receiver-poll
     :poll-eintr-ordinal 1}}
   {:id :jolt.example.outbox.regime/http-first-poll-eintr-2
    :label "HTTP poll first; EINTR at poll 2"
    :summary (str "Admit the HTTP reactor's first poll before the receiver "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 2.")
    :scope regime-scope
    :coordinates
    {:admission-plan :http-poll-then-receiver-poll
     :poll-eintr-ordinal 2}}
   {:id :jolt.example.outbox.regime/http-first-poll-eintr-4
    :label "HTTP poll first; EINTR at poll 4"
    :summary (str "Admit the HTTP reactor's first poll before the receiver "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 4.")
    :scope regime-scope
    :coordinates
    {:admission-plan :http-poll-then-receiver-poll
     :poll-eintr-ordinal 4}}
   {:id :jolt.example.outbox.regime/http-first-poll-eintr-8
    :label "HTTP poll first; EINTR at poll 8"
    :summary (str "Admit the HTTP reactor's first poll before the receiver "
                  "reactor's first poll; capture modeled EINTR at poll "
                  "attempt 8.")
    :scope regime-scope
    :coordinates
    {:admission-plan :http-poll-then-receiver-poll
     :poll-eintr-ordinal 8}}])

(def ^:private regimes-by-id
  (into {} (map (juxt :id identity)) regimes))

(def ^:private base-input-keys
  #{:payload :stream-capacity :pipe-capacity})

(def ^:private supported-stream-capacities #{8 16 32})
(def ^:private supported-pipe-capacities #{1 2 4})
(def ^:private max-payload-octets 32)

(defn regime
  "Returns the immutable descriptor for `id`, or nil when it is unknown."
  [id]
  (get regimes-by-id id))

(defn require-regime
  "Returns the descriptor for `id`, or throws a typed fail-closed error."
  [id]
  (or (regime id)
      (throw
       (ex-info
        "unknown Outbox experiment regime"
        {:type :jolt.example.outbox.regimes/unknown-regime
         :regime-id id}))))

(defn- invalid-base [reason data]
  (ex-info
   "invalid Outbox regime base input"
   (merge {:type :jolt.example.outbox.regimes/invalid-base-input
           :reason reason}
          data)))

(defn- octet? [value]
  (and (integer? value) (<= 0 value 255)))

(defn- validate-base! [base]
  (when-not (map? base)
    (throw (invalid-base :not-a-map {:value base})))
  (when-not (= base-input-keys (set (keys base)))
    (throw
     (invalid-base
      :unexpected-keys
      {:expected-keys base-input-keys
       :actual-keys (set (keys base))})))
  (let [payload (:payload base)]
    (when-not (and (vector? payload)
                   (<= (count payload) max-payload-octets)
                   (every? octet? payload))
      (throw
       (invalid-base
        :invalid-payload
        {:value payload :max-length max-payload-octets}))))
  (when-not (contains? supported-stream-capacities (:stream-capacity base))
    (throw
     (invalid-base
      :invalid-stream-capacity
      {:value (:stream-capacity base)
       :supported (vec (sort supported-stream-capacities))})))
  (when-not (contains? supported-pipe-capacities (:pipe-capacity base))
    (throw
     (invalid-base
      :invalid-pipe-capacity
      {:value (:pipe-capacity base)
       :supported (vec (sort supported-pipe-capacities))})))
  base)

(defn scenario-input
  "Returns one fresh canonical scenario input for a trusted regime.

  The one-argument arity uses `lab-base-input`. The two-argument arity accepts
  only the exact base keys :payload, :stream-capacity, and :pipe-capacity.
  Caller values are canonicalized and restored before the regime's trusted
  two-coordinate map is merged, so metadata and unsupported/mutable aliases
  cannot enter the returned input."
  ([regime-id]
   (scenario-input lab-base-input regime-id))
  ([base regime-id]
   (let [validated (validate-base! base)
         snapshot
         (try
           (trace/restore-value
            (trace/canonical-value validated [:outbox-regime :base-input]))
           (catch :default error
             (throw
              (ex-info
               "Outbox regime base input is not canonical"
               {:type :jolt.example.outbox.regimes/invalid-base-input
                :reason :noncanonical-value}
               error))))
         coordinates (:coordinates (require-regime regime-id))]
     (merge snapshot coordinates))))
