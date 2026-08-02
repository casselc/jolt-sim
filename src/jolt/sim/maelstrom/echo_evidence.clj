(ns jolt.sim.maelstrom.echo-evidence
  "Pure, closed, versioned Echo evidence projection and post-hoc monitors.

  Projects a narrow canonical semantic record from one Echo case's
  memory-transport snapshot and echo input value.  Reuses
  jolt.sim.maelstrom.history/check-snapshot for transport integrity.
  Preserves and correlates the transport enqueue/delivery message-id with
  Maelstrom msg_id and in_reply_to for init, echo request, and echo reply.
  Echo values are frozen through jolt.sim.trace/canonical-value, so mutable
  leaves and host identity cannot leak into the document.  Values outside the
  trace domain fail closed.  The projection otherwise excludes PID,
  timestamps, object identity, and diagnostics from canonical semantic
  evidence.  It fails closed on malformed, noncanonical, or broken transport
  evidence while retaining finite incomplete executions for the monitors.

  Two separate pure post-hoc decisions:

  safety     Every reply that exists has the expected payload, reversed route,
             and in_reply_to.  A missing reply makes the finite observation
             inconclusive, not unsafe.  Validation owns causal delivery
             ordering and transport-ID consistency.

  completion All four roles exist and are delivered and terminal queues are
             empty under recorded assumptions: complete snapshot, one node and
             client, sequential driver, no message faults.  Exact closed event
             coverage derives the maximum of 8 events; it is not a separate
             reachable monitor decision.  Missing and undelivered roles are
             reported together as one finite incompleteness observation.  This
             is not fairness, deadlock freedom, bounded-complete exploration,
             wall-time, OS-scheduler fairness, or unbounded liveness.

  This is an evidence projection only.  No simulator namespace enters the
  ordinary Echo handler or node."
  (:require [jolt.sim.maelstrom.history :as history]
            [jolt.sim.trace :as trace]))

;; ---- public constants --------------------------------------------------------

(def schema
  "The unique schema keyword identifying Echo evidence v1 documents."
  :jolt.sim.maelstrom.echo-evidence/v1)

(def evidence-version
  "The only evidence document version this namespace produces."
  1)

(def completion-event-bound
  "The derived maximum number of transport events for a complete Echo
  round-trip under the recorded assumptions.  Closed evidence has at most four
  enqueue and four matching delivery events, so validation's exact event
  coverage already entails this bound; completion does not branch on it.

  The expected sequence for one node, one client, sequential driver, no faults:
    1  enqueue init request   (client -> node)
    2  enqueue echo request   (client -> node)
    3  deliver init request   (node)
    4  enqueue init_ok reply   (node -> client)
    5  deliver echo request   (node)
    6  enqueue echo_ok reply   (node -> client)
    7  deliver init_ok reply   (client)
    8  deliver echo_ok reply   (client)"
  8)

(def recorded-assumptions
  "The explicit assumptions under which the completion event bound is valid.
  A stable closed vector, not a set."
  [:complete-snapshot
   :one-node-and-client
   :sequential-driver
   :no-message-faults])

;; ---- closed key sets --------------------------------------------------------

(def ^:private known-body-types
  #{"init" "echo" "init_ok" "echo_ok"})

(def ^:private evidence-top-keys
  #{:jolt.sim.maelstrom.echo-evidence/schema
    :jolt.sim.maelstrom.echo-evidence/version
    :echo-input
    :init-request
    :init-reply
    :echo-request
    :echo-reply
    :transport-integrity
    :event-count
    :terminal-queues
    :assumptions})

(def ^:private base-message-keys
  "Keys present in every message observation record."
  #{:msg-id :in-reply-to :src :dest
    :transport-enqueue-ordinal
    :transport-enqueue-message-id
    :transport-deliver-ordinal
    :transport-deliver-message-id})

(def ^:private echo-message-keys
  "Keys for an echo request or echo reply observation."
  (conj base-message-keys :echo))

(def ^:private role-specs
  "Metadata per role: is it a request (nil in-reply-to), does it carry :echo."
  {:init-request {:request? true  :echo? false :keys base-message-keys}
   :init-reply   {:request? false :echo? false :keys base-message-keys}
   :echo-request {:request? true  :echo? true  :keys echo-message-keys}
   :echo-reply   {:request? false :echo? true  :keys echo-message-keys}})

;; ---- internal helpers ------------------------------------------------------

(defn- fail! [type message data]
  (ex-info message (assoc data :type type)))

(defn- positive-integer? [v]
  (and (integer? v) (pos? v)))

(defn- non-negative-integer? [v]
  (and (integer? v) (not (neg? v))))

(defn- valid-snapshot-structure? [v]
  (and (map? v)
       (contains? v :queues)
       (contains? v :history)
       (vector? (:history v))))

(defn- deliver-lookup
  "Builds a transport message-id -> deliver event map from history events."
  [history]
  (into {}
        (keep (fn [event]
                (when (= :deliver (:op event))
                  [(:message-id event) event]))
              history)))

(defn- observe-message
  "Extracts a per-message observation from an enqueue event and its matching
  deliver event.  Delivery fields are always present; nil means undelivered.
  Fails closed on missing or malformed body fields."
  [enqueue-event del-lookup]
  (let [envelope (:envelope enqueue-event)
        body    (:body envelope)
        msg-id  (:msg_id body)
        irt     (:in_reply_to body)
        mid     (:message-id enqueue-event)
        del     (get del-lookup mid)]
    (when-not (non-negative-integer? msg-id)
      (throw (fail! ::malformed-evidence
                     "message body has no non-negative integer msg_id"
                     {:body body})))
    (when (and (some? irt) (not (non-negative-integer? irt)))
      (throw (fail! ::malformed-evidence
                     "message body in_reply_to is present but not a non-negative integer"
                     {:body body})))
    (when-not (string? (:src envelope))
      (throw (fail! ::malformed-evidence
                     "envelope :src is not a string"
                     {:envelope envelope})))
    (when-not (string? (:dest envelope))
      (throw (fail! ::malformed-evidence
                     "envelope :dest is not a string"
                     {:envelope envelope})))
    {:msg-id msg-id
     :in-reply-to irt
     :src (:src envelope)
     :dest (:dest envelope)
     :transport-enqueue-ordinal (:ordinal enqueue-event)
     :transport-enqueue-message-id mid
     :transport-deliver-ordinal (when del (:ordinal del))
     :transport-deliver-message-id (when del (:message-id del))}))

(defn- classify-enqueues
  "Partitions enqueue events into known body-type buckets and rejects
  unknown types.  Returns {:known {type [events]} :unknown #{type}}."
  [enqueues]
  (loop [events (seq enqueues)
         known {}
         unknown #{}]
    (if-let [event (first events)]
      (let [t (get-in event [:envelope :body :type])]
        (if (known-body-types t)
          (recur (next events)
                 (update known t (fnil conj []) event)
                 unknown)
          (recur (next events) known (conj unknown t))))
      {:known known :unknown-types unknown})))

(defn- nonempty-endpoints
  "Returns a stable sorted vector of endpoint names that have non-empty
  residual queues in the snapshot."
  [queues]
  (vec (sort (keep (fn [[ep q]] (when (seq q) ep)) queues))))

;; ---- public validation -----------------------------------------------------

(defn validate-evidence!
  "Validates an evidence document's structural integrity.  Returns evidence
  unchanged on success; throws a typed ex-info on any violation.

  Rejects:
  - wrong, extra, or missing top-level keys
  - schema or version mismatch
  - assumptions vector mismatch
  - noncanonical echo input, request, or reply values
  - invalid event-count
  - malformed transport-integrity summary
  - unstable or delivery-inconsistent terminal-queue form
  - malformed message records (wrong keys, bad types)
  - request :in-reply-to not nil
  - non-integer reply :in-reply-to
  - non-positive transport ordinals or message-ids
  - inconsistent delivery fields (partial present, mismatched IDs)
  - per-endpoint delivery that violates enqueue FIFO, including delivery past
    an earlier undelivered message
  - a topology other than one distinct client/node request route
  - init/echo causal-order or reply-msg-id monotonicity violations
  - causal violation: deliver ordinal not after enqueue ordinal"
  [evidence]
  (when-not (map? evidence)
    (throw (fail! ::invalid-evidence
                   "evidence document is not a map"
                   {:value-class (str (class evidence))})))
  (let [actual-keys (set (keys evidence))]
    (when-not (= evidence-top-keys actual-keys)
      (throw (fail! ::invalid-evidence
                     "evidence document has wrong top-level keys"
                     {:expected evidence-top-keys
                      :actual actual-keys}))))
  (when-not (= schema (:jolt.sim.maelstrom.echo-evidence/schema evidence))
    (throw (fail! ::invalid-evidence
                   "evidence schema mismatch"
                   {:expected schema
                    :actual (:jolt.sim.maelstrom.echo-evidence/schema evidence)})))
  (when-not (= evidence-version
               (:jolt.sim.maelstrom.echo-evidence/version evidence))
    (throw (fail! ::invalid-evidence
                   "evidence version mismatch"
                   {:expected evidence-version
                    :actual (:jolt.sim.maelstrom.echo-evidence/version evidence)})))
  (when-not (= recorded-assumptions (:assumptions evidence))
    (throw (fail! ::invalid-evidence
                   "evidence assumptions mismatch"
                   {:expected recorded-assumptions
                    :actual (:assumptions evidence)})))
  (when-not (trace/canonical-form? (:echo-input evidence))
    (throw (fail! ::invalid-evidence
                   "echo-input is not a canonical trace value"
                   {:echo-input (:echo-input evidence)})))
  (when-not (non-negative-integer? (:event-count evidence))
    (throw (fail! ::invalid-evidence
                   "event-count is not a non-negative integer"
                   {:event-count (:event-count evidence)})))
  ;; Projection accepts only the closed passing summary emitted by the
  ;; transport checker.  A violation belongs outside this evidence domain;
  ;; silently retaining one would let the semantic monitors ignore it.
  (let [ti (:transport-integrity evidence)]
    (when-not (and (map? ti)
                   (= #{:status :events :enqueued :delivered}
                      (set (keys ti))))
      (throw (fail! ::invalid-evidence
                     "transport-integrity has the wrong shape"
                     {:transport-integrity ti})))
    (when-not (= :pass (:status ti))
      (throw (fail! ::invalid-evidence
                     "transport-integrity is not passing"
                     {:status (:status ti)})))
    (when-not (every? non-negative-integer?
                      [(:events ti) (:enqueued ti) (:delivered ti)])
      (throw (fail! ::invalid-evidence
                     "transport-integrity counters are invalid"
                     {:transport-integrity ti})))
    (when-not (= (:event-count evidence) (:events ti))
      (throw (fail! ::invalid-evidence
                     "event-count disagrees with transport-integrity"
                     {:event-count (:event-count evidence)
                      :transport-events (:events ti)}))))
  ;; terminal-queues must be a sorted vector of strings
  (let [tq (:terminal-queues evidence)]
    (when-not (vector? tq)
      (throw (fail! ::invalid-evidence
                     "terminal-queues is not a vector"
                     {:terminal-queues tq})))
    (when-not (= tq (vec (sort (distinct tq))))
      (throw (fail! ::invalid-evidence
                     "terminal-queues is not sorted and distinct"
                     {:terminal-queues tq})))
    (doseq [ep tq]
      (when-not (string? ep)
        (throw (fail! ::invalid-evidence
                       "terminal-queues contains a non-string endpoint"
                       {:endpoint ep})))))
  (doseq [role [:init-request :echo-request]]
    (when-not (map? (get evidence role))
      (throw (fail! ::invalid-evidence
                     (str role " must be present")
                     {:role role :record (get evidence role)}))))
  ;; Validate each non-nil message record. Replies may be nil so a finite
  ;; incomplete execution remains monitorable.
  (doseq [[role-kw record] (select-keys evidence
                                    [:init-request :init-reply
                                     :echo-request :echo-reply])]
    (when (some? record)
      (let [spec (role-specs role-kw)
            expected (spec :keys)]
        ;; key set
        (when-not (= expected (set (keys record)))
          (throw (fail! ::invalid-evidence
                         (str role-kw " record has wrong keys")
                         {:role role-kw
                          :expected expected
                          :actual (set (keys record))})))
        ;; :msg-id
        (when-not (non-negative-integer? (:msg-id record))
          (throw (fail! ::invalid-evidence
                         (str role-kw " :msg-id is not a non-negative integer")
                         {:role role-kw :msg-id (:msg-id record)})))
        ;; :in-reply-to
        (let [irt (:in-reply-to record)]
          (if (:request? spec)
            (when (some? irt)
              (throw (fail! ::invalid-evidence
                             (str role-kw " is a request but :in-reply-to is not nil")
                             {:role role-kw :in-reply-to irt})))
            (when-not (non-negative-integer? irt)
              (throw (fail! ::invalid-evidence
                             (str role-kw " is a reply but :in-reply-to is not a non-negative integer")
                             {:role role-kw :in-reply-to irt})))))
        ;; :src, :dest
        (doseq [k [:src :dest]]
          (when-not (string? (get record k))
            (throw (fail! ::invalid-evidence
                           (str role-kw " " (name k) " is not a string")
                           {:role role-kw k (get record k)}))))
        ;; transport enqueue fields
        (when-not (positive-integer? (:transport-enqueue-ordinal record))
          (throw (fail! ::invalid-evidence
                         (str role-kw " :transport-enqueue-ordinal is not a positive integer")
                         {:role role-kw
                          :transport-enqueue-ordinal (:transport-enqueue-ordinal record)})))
        (when-not (positive-integer? (:transport-enqueue-message-id record))
          (throw (fail! ::invalid-evidence
                         (str role-kw " :transport-enqueue-message-id is not a positive integer")
                         {:role role-kw
                          :transport-enqueue-message-id (:transport-enqueue-message-id record)})))
        (when-not (= (:transport-enqueue-ordinal record)
                     (:transport-enqueue-message-id record))
          (throw (fail! ::invalid-evidence
                         (str role-kw " enqueue ordinal and transport identity differ")
                         {:role role-kw
                          :transport-enqueue-ordinal
                          (:transport-enqueue-ordinal record)
                          :transport-enqueue-message-id
                          (:transport-enqueue-message-id record)})))
        ;; delivery fields: both nil or both present and consistent
        (let [del-ord (:transport-deliver-ordinal record)
              del-mid (:transport-deliver-message-id record)
              eq-mid  (:transport-enqueue-message-id record)]
          (cond
            (and (some? del-ord) (nil? del-mid))
            (throw (fail! ::invalid-evidence
                           (str role-kw " has deliver ordinal but no deliver message-id")
                           {:role role-kw
                            :transport-deliver-ordinal del-ord
                            :transport-deliver-message-id del-mid}))
            (and (nil? del-ord) (some? del-mid))
            (throw (fail! ::invalid-evidence
                           (str role-kw " has deliver message-id but no deliver ordinal")
                           {:role role-kw
                            :transport-deliver-ordinal del-ord
                            :transport-deliver-message-id del-mid}))
            (and (some? del-ord)
                 (not (positive-integer? del-ord)))
            (throw (fail! ::invalid-evidence
                           (str role-kw " :transport-deliver-ordinal is not a positive integer")
                           {:role role-kw
                            :transport-deliver-ordinal del-ord}))
            (and (some? del-mid)
                 (not (positive-integer? del-mid)))
            (throw (fail! ::invalid-evidence
                           (str role-kw " :transport-deliver-message-id is not a positive integer")
                           {:role role-kw
                            :transport-deliver-message-id del-mid}))
            (and (some? del-ord)
                 (not= del-mid eq-mid))
            (throw (fail! ::invalid-evidence
                           (str role-kw " deliver message-id does not match enqueue message-id")
                           {:role role-kw
                            :enqueue-mid eq-mid
                            :deliver-mid del-mid})))
        ;; causal: deliver must be after enqueue
        (when (and (some? del-ord)
                   (<= del-ord (:transport-enqueue-ordinal record)))
          (throw (fail! ::invalid-evidence
                         (str role-kw " deliver ordinal is not after enqueue ordinal")
                         {:role role-kw
                          :enqueue-ordinal (:transport-enqueue-ordinal record)
                          :deliver-ordinal del-ord}))))
        ;; :echo field for echo roles
        (when (:echo? spec)
          (when-not (trace/canonical-form? (:echo record))
            (throw (fail! ::invalid-evidence
                           (str role-kw " :echo is not a canonical trace value")
                           {:role role-kw :echo (:echo record)})))))))

  ;; The recorded one-client/one-node sequential driver has one request route
  ;; and processes init before echo.  These are evidence-domain facts, not
  ;; application safety decisions, so reject documents that merely repeat the
  ;; assumption label without satisfying it.
  (let [init-request (:init-request evidence)
        echo-request (:echo-request evidence)
        init-route [(:src init-request) (:dest init-request)]
        echo-route [(:src echo-request) (:dest echo-request)]
        init-enqueue (:transport-enqueue-ordinal init-request)
        echo-enqueue (:transport-enqueue-ordinal echo-request)
        init-deliver (:transport-deliver-ordinal init-request)
        echo-deliver (:transport-deliver-ordinal echo-request)]
    (when-not (= init-route echo-route)
      (throw (fail! ::invalid-evidence
                     "init and echo requests do not share one client/node route"
                     {:init-route init-route :echo-route echo-route})))
    (when (= (:src init-request) (:dest init-request))
      (throw (fail! ::invalid-evidence
                     "client and node endpoints are not distinct"
                     {:endpoint (:src init-request)})))
    (when-not (< init-enqueue echo-enqueue)
      (throw (fail! ::invalid-evidence
                     "echo request was enqueued before init request"
                     {:init-enqueue-ordinal init-enqueue
                      :echo-enqueue-ordinal echo-enqueue})))
    (when (and (some? echo-deliver) (nil? init-deliver))
      (throw (fail! ::invalid-evidence
                     "echo request was delivered before init request"
                     {:init-deliver-ordinal init-deliver
                      :echo-deliver-ordinal echo-deliver})))
    (when (and (some? echo-deliver)
               (some? init-deliver)
               (not (< init-deliver echo-deliver)))
      (throw (fail! ::invalid-evidence
                     "echo request delivery did not follow init request delivery"
                     {:init-deliver-ordinal init-deliver
                      :echo-deliver-ordinal echo-deliver}))))
  ;; The unchanged node allocates outbound msg_id values monotonically from one
  ;; source.  Missing replies remain monitorable, but two present replies must
  ;; retain that source-local order.
  (let [init-reply (:init-reply evidence)
        echo-reply (:echo-reply evidence)
        echo-deliver (get-in evidence
                             [:echo-request :transport-deliver-ordinal])]
    (when (and init-reply echo-deliver
               (not (< (:transport-enqueue-ordinal init-reply)
                       echo-deliver)))
      (throw (fail! ::invalid-evidence
                     "init reply enqueue did not precede echo request delivery"
                     {:init-reply-enqueue-ordinal
                      (:transport-enqueue-ordinal init-reply)
                      :echo-deliver-ordinal echo-deliver})))
    (when (and init-reply echo-reply
               (not (< (:msg-id init-reply) (:msg-id echo-reply))))
      (throw (fail! ::invalid-evidence
                     "reply msg_id values are not source-local monotonic"
                     {:init-reply-msg-id (:msg-id init-reply)
                      :echo-reply-msg-id (:msg-id echo-reply)}))))

  ;; The projection owns every event in this closed Echo case. Pin exact event
  ;; identity/coverage so a hand-mutated document cannot hide an extra event,
  ;; reuse one transport record for two roles, or forge a smaller bound.
  (let [records (vec (keep #(get evidence %)
                           [:init-request :init-reply
                            :echo-request :echo-reply]))
        enqueue-ordinals (mapv :transport-enqueue-ordinal records)
        delivered-records (filterv #(some? (:transport-deliver-ordinal %))
                                   records)
        deliver-ordinals (mapv :transport-deliver-ordinal delivered-records)
        all-ordinals (vec (sort (concat enqueue-ordinals deliver-ordinals)))
        expected-ordinals (mapv inc (range (:event-count evidence)))
        expected-terminal-queues
        (vec (sort (distinct
                    (keep (fn [record]
                            (when (nil? (:transport-deliver-ordinal record))
                              (:dest record)))
                          records))))
        ti (:transport-integrity evidence)]
    ;; A projected document must retain the memory transport's per-endpoint
    ;; FIFO semantics, not merely plausible per-message causality. Delivery
    ;; records for one destination form an enqueue-ordered prefix: delivery
    ;; ordinals rise in that order, and once one record is undelivered no later
    ;; enqueue to the same destination may be delivered.
    (doseq [endpoint (sort (distinct (map :dest records)))]
      (loop [remaining
             (seq (sort-by :transport-enqueue-ordinal
                           (filterv #(= endpoint (:dest %)) records)))
             previous-delivered nil
             first-undelivered nil]
        (when-let [record (first remaining)]
          (let [deliver-ordinal (:transport-deliver-ordinal record)]
            (cond
              (nil? deliver-ordinal)
              (recur (next remaining)
                     previous-delivered
                     (or first-undelivered record))

              first-undelivered
              (throw (fail! ::invalid-evidence
                             "delivery skipped an earlier undelivered enqueue at the same endpoint"
                             {:endpoint endpoint
                              :earlier-enqueue-message-id
                              (:transport-enqueue-message-id first-undelivered)
                              :later-enqueue-message-id
                              (:transport-enqueue-message-id record)
                              :later-deliver-ordinal deliver-ordinal}))

              (and previous-delivered
                   (not (< (:transport-deliver-ordinal previous-delivered)
                           deliver-ordinal)))
              (throw (fail! ::invalid-evidence
                             "deliveries do not preserve per-endpoint enqueue FIFO"
                             {:endpoint endpoint
                              :earlier-enqueue-message-id
                              (:transport-enqueue-message-id previous-delivered)
                              :earlier-deliver-ordinal
                              (:transport-deliver-ordinal previous-delivered)
                              :later-enqueue-message-id
                              (:transport-enqueue-message-id record)
                              :later-deliver-ordinal deliver-ordinal}))

              :else
              (recur (next remaining) record nil))))))
    (when-not (= expected-ordinals all-ordinals)
      (throw (fail! ::invalid-evidence
                     "projected roles do not account for every transport event exactly once"
                     {:expected-ordinals expected-ordinals
                      :actual-ordinals all-ordinals})))
    (when-not (= (count records) (:enqueued ti))
      (throw (fail! ::invalid-evidence
                     "projected role count disagrees with enqueued count"
                     {:projected (count records) :enqueued (:enqueued ti)})))
    (when-not (= (count delivered-records) (:delivered ti))
      (throw (fail! ::invalid-evidence
                     "projected delivery count disagrees with transport count"
                     {:projected (count delivered-records)
                      :delivered (:delivered ti)})))
    (when-not (= expected-terminal-queues (:terminal-queues evidence))
      (throw (fail! ::invalid-evidence
                     "terminal queues disagree with present undelivered roles"
                     {:expected expected-terminal-queues
                      :actual (:terminal-queues evidence)})))
    (doseq [[request-role reply-role]
            [[:init-request :init-reply]
             [:echo-request :echo-reply]]]
      (let [request (get evidence request-role)
            reply (get evidence reply-role)]
        (when (and reply (nil? (:transport-deliver-ordinal request)))
          (throw (fail! ::invalid-evidence
                         "reply exists although its request was never delivered"
                         {:request-role request-role
                          :reply-role reply-role})))
        (when (and reply
                   (<= (:transport-enqueue-ordinal reply)
                       (:transport-deliver-ordinal request)))
          (throw (fail! ::invalid-evidence
                         "reply was enqueued before its request was delivered"
                         {:request-role request-role
                          :reply-role reply-role
                          :request-deliver-ordinal
                          (:transport-deliver-ordinal request)
                          :reply-enqueue-ordinal
                          (:transport-enqueue-ordinal reply)}))))))
  evidence)

;; ---- public projection ------------------------------------------------------

(defn project-evidence
  "Projects a closed versioned Echo evidence document from one finite case's
  memory-transport snapshot and the echo input value.

  The snapshot must be the value returned by
  jolt.maelstrom.transport.memory/snapshot.  It must first pass
  jolt.sim.maelstrom.history/check-snapshot.  The history must contain
  exactly one init request and one echo request; zero or one init_ok
  reply and zero or one echo_ok reply.  No enqueue event may carry an
  unknown body type.

  Echo input, request, and reply values are frozen as
  jolt.sim.trace/canonical-value forms.  Returns a deterministic,
  structurally stable map with per-message causal observations, the transport
  integrity result, event count, terminal queue state, and recorded
  assumptions.  Same logical input and snapshot produce structurally equal
  evidence.  Terminal queues are a sorted vector of nonempty endpoint strings.

  Excludes PID, timestamps, object identity, and diagnostics.  Fails closed on
  malformed, noncanonical, or broken transport evidence while retaining
  incomplete finite cases for the monitors.

  Throws ex-info tagged ::transport-integrity-failed,
  ::malformed-snapshot, ::missing-observation,
  ::unknown-message-type, ::malformed-evidence, or ::invalid-evidence.
  Unsupported echo values surface jolt.sim.trace/unsupported-value."
  [snapshot echo-input]
  (when-not (valid-snapshot-structure? snapshot)
    (throw (fail! ::malformed-snapshot
                   "snapshot must be a map with :queues and :history (vector)"
                   {:value-class (when-not (map? snapshot)
                                  (str (class snapshot)))})))
  (let [transport-check (history/check-snapshot snapshot)]
    (when-not (= :pass (:status transport-check))
      (throw (fail! ::transport-integrity-failed
                     "Echo evidence requires passing transport integrity"
                     {:transport-check transport-check})))
  (let [history  (:history snapshot)
        queues   (:queues snapshot)
        enqueues (filterv #(= :enqueue (:op %)) history)
        dels     (deliver-lookup history)
        classified (classify-enqueues enqueues)]
    ;; Reject unknown message types
    (when (seq (:unknown-types classified))
      (throw (fail! ::unknown-message-type
                     "transport history contains unknown body types"
                     {:unknown-types (:unknown-types classified)})))
    ;; Exactly one init request and one echo request
    (let [init-reqs (get-in classified [:known "init"])
          echo-reqs (get-in classified [:known "echo"])
          init-reps (get-in classified [:known "init_ok"])
          echo-reps (get-in classified [:known "echo_ok"])]
      (when-not (= 1 (count init-reqs))
        (throw (fail! ::missing-observation
                       (if (zero? (count init-reqs))
                         "no init request found in transport history"
                         "multiple init requests found in transport history")
                       {:role :init-request :count (count init-reqs)})))
      (when-not (= 1 (count echo-reqs))
        (throw (fail! ::missing-observation
                       (if (zero? (count echo-reqs))
                         "no echo request found in transport history"
                         "multiple echo requests found in transport history")
                       {:role :echo-request :count (count echo-reqs)})))
      ;; Allow 0 or 1 reply
      (when (> (count init-reps) 1)
        (throw (fail! ::missing-observation
                       "multiple init_ok replies found in transport history"
                       {:role :init-reply :count (count init-reps)})))
      (when (> (count echo-reps) 1)
        (throw (fail! ::missing-observation
                       "multiple echo_ok replies found in transport history"
                       {:role :echo-reply :count (count echo-reps)})))
      ;; Extract echo payload from request and reply (must be present when
      ;; the request exists, which it does)
      (let [echo-req-body (get-in (first echo-reqs) [:envelope :body])
            echo-rep-body (when (seq echo-reps)
                             (get-in (first echo-reps) [:envelope :body]))]
        (when-not (contains? echo-req-body :echo)
          (throw (fail! ::malformed-evidence
                         "echo request body has no :echo key"
                         {:body echo-req-body})))
        ;; echo reply body must also have :echo when present
        (when (and (seq echo-reps)
                   (not (contains? echo-rep-body :echo)))
          (throw (fail! ::malformed-evidence
                         "echo reply body has no :echo key"
                         {:body echo-rep-body})))
        (let [canonical-input
              (trace/canonical-value echo-input [:echo-input])
              canonical-request
              (trace/canonical-value (:echo echo-req-body)
                                     [:echo-request :echo])
              canonical-reply
              (when (seq echo-reps)
                (trace/canonical-value (:echo echo-rep-body)
                                       [:echo-reply :echo]))]
          (validate-evidence!
           {:jolt.sim.maelstrom.echo-evidence/schema schema
            :jolt.sim.maelstrom.echo-evidence/version evidence-version
            :echo-input canonical-input
            :init-request (observe-message (first init-reqs) dels)
            :init-reply (when (seq init-reps)
                          (observe-message (first init-reps) dels))
            :echo-request (assoc (observe-message (first echo-reqs) dels)
                                 :echo canonical-request)
            :echo-reply (when (seq echo-reps)
                          (assoc (observe-message (first echo-reps) dels)
                                 :echo canonical-reply))
            :transport-integrity transport-check
            :event-count (count history)
            :terminal-queues (nonempty-endpoints queues)
            :assumptions recorded-assumptions})))))))

;; ---- public monitors -------------------------------------------------------

(defn check-safety
  "Pure post-hoc safety decision for Echo evidence.

  Validates structural integrity via validate-evidence!, then checks:

  - :echo-input equals the request echo payload.
  - Every present reply has the expected payload and reversed src/dest.
  - Every present reply in_reply_to correlates to its request msg_id.
  - Transport-ID consistency and causal delivery ordering are already
    enforced by validate-evidence!.

  Missing replies are not a finite safety counterexample.  When every present
  reply is safe but one or more replies are absent, returns
  {:status :inconclusive
   :detail {:reason :replies-missing :missing [...]}}.  Otherwise returns
  {:status :pass} or {:status :violation :detail {:reason <keyword> ...}}."
  [evidence]
  (validate-evidence! evidence)
  (let [ireq (:init-request evidence)
        irep (:init-reply evidence)
        ereq (:echo-request evidence)
        erep (:echo-reply evidence)
        missing (vec (keep (fn [role]
                             (when (nil? (get evidence role)) role))
                           [:init-reply :echo-reply]))]
    (cond
      (not= (:echo-input evidence) (:echo ereq))
      {:status :violation
       :detail {:reason :echo-input-mismatch
                :echo-input (:echo-input evidence)
                :request-echo (:echo ereq)}}

      (and erep (not= (:echo ereq) (:echo erep)))
      {:status :violation
       :detail {:reason :echo-value-mismatch
                :request-echo (:echo ereq)
                :reply-echo   (:echo erep)}}

      (and irep (not= (:src irep) (:dest ireq)))
      {:status :violation
       :detail {:reason :init-source-dest-not-reversed
                :request-src (:src ireq)
                :request-dest (:dest ireq)
                :reply-src   (:src irep)
                :reply-dest   (:dest irep)}}

      (and irep (not= (:dest irep) (:src ireq)))
      {:status :violation
       :detail {:reason :init-dest-src-not-reversed
                :request-src (:src ireq)
                :request-dest (:dest ireq)
                :reply-src   (:src irep)
                :reply-dest   (:dest irep)}}

      (and erep (not= (:src erep) (:dest ereq)))
      {:status :violation
       :detail {:reason :echo-source-dest-not-reversed
                :request-src (:src ereq)
                :request-dest (:dest ereq)
                :reply-src   (:src erep)
                :reply-dest   (:dest erep)}}

      (and erep (not= (:dest erep) (:src ereq)))
      {:status :violation
       :detail {:reason :echo-dest-src-not-reversed
                :request-src (:src ereq)
                :request-dest (:dest ereq)
                :reply-src   (:src erep)
                :reply-dest   (:dest erep)}}

      (and irep (not= (:msg-id ireq) (:in-reply-to irep)))
      {:status :violation
       :detail {:reason :init-correlation-mismatch
                :request-msg-id    (:msg-id ireq)
                :reply-in-reply-to (:in-reply-to irep)}}

      (and erep (not= (:msg-id ereq) (:in-reply-to erep)))
      {:status :violation
       :detail {:reason :echo-correlation-mismatch
                :request-msg-id    (:msg-id ereq)
                :reply-in-reply-to (:in-reply-to erep)}}

      (seq missing)
      {:status :inconclusive
       :detail {:reason :replies-missing
                :missing missing}}

      :else
      {:status :pass})))

(defn check-completion
  "Pure post-hoc finite-completion decision for Echo evidence.

  Validates structural integrity via validate-evidence!, then verifies three
  conditions under the recorded assumptions (complete snapshot,
  one node and client, sequential driver, no message faults):

  1. All four role records are present (init-request, init-reply,
     echo-request, echo-reply).
  2. All four roles are delivered (have a transport-deliver-ordinal).
  3. Terminal queues are empty (no undelivered messages remain).

  Exact closed role/event coverage in validate-evidence! derives an event count
  no greater than completion-event-bound; there is no separate reachable event
  bound decision.  Missing roles, present undelivered roles, and their exact
  residual terminal endpoints are returned together in one :incomplete
  violation.  This is a monitored finite trace, not fairness, deadlock freedom,
  bounded-complete exploration, wall-clock progress, OS-scheduler fairness, or
  any unbounded liveness property.

  Returns {:status :pass} or
  {:status :violation :detail {:reason <keyword> ...}}."
  [evidence]
  (validate-evidence! evidence)
  (let [roles [:init-request :init-reply :echo-request :echo-reply]
        missing (vec (keep (fn [r] (when (nil? (get evidence r)) r)) roles))
        undelivered (vec (keep
                          (fn [r]
                            (let [rec (get evidence r)]
                              (when (and (some? rec)
                                       (nil? (:transport-deliver-ordinal rec)))
                                r)))
                          roles))
        terminal-queues (:terminal-queues evidence)]
    (if (or (seq missing) (seq undelivered) (seq terminal-queues))
      {:status :violation
       :detail {:reason :incomplete
                :missing missing
                :undelivered undelivered
                :terminal-queues terminal-queues}}
      {:status :pass})))
