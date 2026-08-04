(ns jolt.sim.maelstrom.history
  "A pure, simulation-owned offline checker for a memory transport snapshot.

  Given a snapshot produced by jolt.maelstrom.transport.memory/snapshot, this
  returns a deterministic pass or violation value describing whether the
  recorded history and residual queues could have been produced by that
  transport's single-atom CAS loop.

  The checker inspects only the transport-level evidence shape: contiguous
  positive ordinals, stable per-message identity (the enqueue event's own
  ordinal), delivery ordering against enqueue, endpoint and envelope agreement
  between an enqueue and its delivery, per-endpoint FIFO, and agreement between
  residual queues and enqueued-but-undelivered envelopes.

  It deliberately does not interpret Maelstrom node or Echo body semantics,
  and it treats envelope leaves as ordinary immutable values for structural
  comparison only -- never as a canonical or encoded trace domain. A snapshot
  whose envelopes hold mutable leaves is outside this checker's evidence
  domain; such leaves are compared by host equality and are not canonicalized.")

(defn- violation
  ([reason]
   {:status :violation :reason reason})
  ([reason detail]
   {:status :violation :reason reason :detail detail}))

(def ^:private event-ops #{:enqueue :deliver})
(def ^:private snapshot-keys #{:queues :history})
(def ^:private event-keys
  #{:ordinal :op :endpoint :envelope :message-id})

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- valid-event? [event]
  (and (map? event)
       (= event-keys (set (keys event)))
       (positive-integer? (:ordinal event))
       (contains? event-ops (:op event))
       (string? (:endpoint event))
       (map? (:envelope event))
       (positive-integer? (:message-id event))))

(defn- valid-queues? [queues]
  (and (map? queues)
       (every? (fn [[endpoint queue]]
                 (and (string? endpoint)
                      (vector? queue)
                      (seq queue)
                      (every? map? queue)))
               queues)))

(defn- valid-snapshot? [snapshot]
  (and (map? snapshot)
       (= snapshot-keys (set (keys snapshot)))
       (valid-queues? (:queues snapshot))
       (vector? (:history snapshot))
       (every? valid-event? (:history snapshot))))

(defn- malformed-reason [snapshot]
  (cond
    (not (map? snapshot))                       :snapshot-not-a-map
    (not= snapshot-keys (set (keys snapshot)))  :snapshot-wrong-keys
    (not (map? (:queues snapshot)))             :queues-not-a-map
    (not (vector? (:history snapshot)))         :history-not-a-vector
    (not (valid-queues? (:queues snapshot)))    :queues-malformed
    (not (every? valid-event? (:history snapshot))) :event-malformed
    :else                                       :unknown))

(defn- first-duplicate
  "Returns the first value seen twice in coll, or nil if all values are
  distinct. Deterministic by input order."
  [coll]
  (loop [seen #{}, xs (seq coll)]
    (when-let [x (first xs)]
      (if (contains? seen x)
        x
        (recur (conj seen x) (next xs))))))

(defn- fold-deliver
  "Returns a violation map for one deliver event, or nil if it is consistent
  with the fold state. State keys: :enqueue-by-id (message-id -> enqueue
  event), :enqueue-order (endpoint -> vector of message-ids in enqueue order),
  :delivered (set of delivered message-ids), :delivered-count (endpoint ->
  count delivered so far)."
  [event state]
  (let [id  (:message-id event)
        ep  (:endpoint event)
        ord (:ordinal event)
        enq (get (:enqueue-by-id state) id)]
    (cond
      (nil? enq)
      (violation :delivery-without-enqueue
                 {:message-id id :ordinal ord})

      (> (:ordinal enq) ord)
      (violation :delivery-before-enqueue
                 {:message-id id
                  :deliver-ordinal ord
                  :enqueue-ordinal (:ordinal enq)})

      (contains? (:delivered state) id)
      (violation :duplicate-delivery
                 {:message-id id :ordinal ord})

      (not= (:endpoint enq) ep)
      (violation :endpoint-mismatch
                 {:message-id id
                  :enqueue-endpoint (:endpoint enq)
                  :deliver-endpoint ep})

      (not= (:envelope enq) (:envelope event))
      (violation :envelope-mismatch
                 {:message-id id
                  :deliver-ordinal ord
                  :enqueue-ordinal (:ordinal enq)})

      :else
      (let [order (get (:enqueue-order state) ep [])
            cnt   (get (:delivered-count state) ep 0)
            total (count order)]
        (cond
          (>= cnt total)
          (violation :fifo-violation
                     {:message-id id :endpoint ep
                      :next-expected nil
                      :delivered-so-far cnt
                      :enqueued-total total})

          (not= id (nth order cnt))
          (violation :fifo-violation
                     {:message-id id :endpoint ep
                      :next-expected (nth order cnt)
                      :delivered-so-far cnt})

          :else nil)))))

(defn- fold-history
  "Folds history (already shape- and ordinal-validated) in event order. Returns
  a violation map, or a final state map carrying per-endpoint delivered counts
  plus summary tallies."
  [history enqueue-by-id enqueue-order]
  (loop [events (seq history)
         state {:enqueue-by-id enqueue-by-id
                :enqueue-order enqueue-order
                :delivered #{}
                :delivered-count {}
                :enqueued (count enqueue-by-id)
                :delivered-total 0}]
    (if-let [event (first events)]
      (if (= :enqueue (:op event))
        (recur (next events) state)
        (if-let [bad (fold-deliver event state)]
          bad
          (let [id (:message-id event)
                ep (:endpoint event)]
            (recur (next events)
                   (-> state
                       (update :delivered conj id)
                       (update :delivered-count
                               (fn [m]
                                 (assoc m ep (inc (get m ep 0)))))
                       (update :delivered-total inc))))))
      state)))

(defn- check-residual
  "Returns a violation map, or a pass result. Compares each endpoint's snapshot
  queue against the envelopes of its enqueued-but-undelivered message-ids, in
  enqueue order."
  [snapshot enqueue-by-id enqueue-order fold-state]
  (let [snap-queues (:queues snapshot)
        delivered-count (:delivered-count fold-state)
        endpoints (vec (sort (set (concat (keys enqueue-order)
                                          (keys snap-queues)))))]
    (loop [eps (seq endpoints)]
      (if-let [ep (first eps)]
        (let [order (get enqueue-order ep [])
              dc   (get delivered-count ep 0)
              expected (mapv #(:envelope (get enqueue-by-id %))
                             (drop dc order))
              actual   (get snap-queues ep [])]
          (cond
            (not= (count expected) (count actual))
            (violation :residual-queue-disagreement
                       {:endpoint ep
                        :expected-count (count expected)
                        :actual-count (count actual)
                        :expected expected
                        :actual actual})

            (not= expected actual)
            (violation :residual-queue-disagreement
                       {:endpoint ep :expected expected :actual actual})

            :else
            (recur (next eps))))
        {:status :pass
         :events (count (:history snapshot))
         :enqueued (:enqueued fold-state)
         :delivered (:delivered-total fold-state)}))))

(defn check-snapshot
  "Returns a deterministic pass or violation value for a memory transport
  snapshot (the value returned by jolt.maelstrom.transport.memory/snapshot).

  The result is a map:

    {:status :pass :events N :enqueued M :delivered K}
    {:status :violation :reason <keyword> :detail <map>}

  The first violated rule wins. Reasons are reported in this checking order:
  :snapshot-not-a-map / :snapshot-wrong-keys / :queues-not-a-map /
  :history-not-a-vector /
  :queues-malformed / :event-malformed (grouped as a :malformed-snapshot
  reason category on :reason), then :noncontiguous-ordinals,
  :duplicate-enqueue-identity, :enqueue-identity-mismatch,
  :enqueue-route-mismatch,
  :delivery-without-enqueue,
  :delivery-before-enqueue, :duplicate-delivery, :endpoint-mismatch,
  :envelope-mismatch, :fifo-violation, and :residual-queue-disagreement.

  The checker is pure: it never calls into the transport and never mutates its
  input. Envelopes are compared structurally as ordinary immutable values for
  delivery agreement and residual agreement only -- this is not a claim that
  mutable envelope leaves are a canonical or encoded trace domain."
  [snapshot]
  (if-not (valid-snapshot? snapshot)
    (violation :malformed-snapshot {:reason (malformed-reason snapshot)})
    (let [history (:history snapshot)
          ordinals (mapv :ordinal history)
          expected-ordinals (mapv inc (range (count history)))]
      (cond
        (not= ordinals expected-ordinals)
        (violation :noncontiguous-ordinals
                   {:expected expected-ordinals :actual ordinals})

        :else
        (let [enqueue-events (filterv #(= :enqueue (:op %)) history)
              enqueue-ids (mapv :message-id enqueue-events)
              dup-id (first-duplicate enqueue-ids)
              identity-mismatch
              (first (filterv #(not= (:ordinal %) (:message-id %))
                              enqueue-events))
              route-mismatch
              (first (filterv #(not= (:endpoint %)
                                     (get-in % [:envelope :dest]))
                              enqueue-events))]
          (cond
            (some? dup-id)
            (violation :duplicate-enqueue-identity {:message-id dup-id})

            (some? identity-mismatch)
            (violation :enqueue-identity-mismatch
                       {:ordinal (:ordinal identity-mismatch)
                        :message-id (:message-id identity-mismatch)})

            (some? route-mismatch)
            (violation :enqueue-route-mismatch
                       {:ordinal (:ordinal route-mismatch)
                        :message-id (:message-id route-mismatch)
                        :endpoint (:endpoint route-mismatch)
                        :envelope-dest (get-in route-mismatch
                                               [:envelope :dest])})

            :else
            (let [enqueue-by-id
                  (into {} (map (fn [ev] [(:message-id ev) ev])) enqueue-events)
                  enqueue-order
                  (into {}
                        (map (fn [[ep evs]] [ep (mapv :message-id evs)]))
                        (group-by :endpoint enqueue-events))
                  fold-state (fold-history history enqueue-by-id enqueue-order)]
              (if (= :violation (:status fold-state))
                fold-state
                (check-residual snapshot enqueue-by-id enqueue-order
                                fold-state)))))))))
