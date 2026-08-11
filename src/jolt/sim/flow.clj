(ns jolt.sim.flow
  "A minimal Mycelium-shaped workflow compiler for the cooperative simulator.

  `compile-workflow` accepts a canonical workflow manifest, a canonical cell
  specification registry, and a trusted, pure closed-over handler table.  The public
  surface deliberately follows Mycelium's accumulating-data model: each cell
  names a cell spec, handlers receive `(ctx state data)` and return exactly
  `{:state next-state :data output-delta}`. The compiler merges that delta into
  the input before routing the accumulated data along workflow edges. Selected
  resources and alias parameters live in ctx rather than the canonical
  accumulating data map.

  This v1 is intentionally smaller than upstream Mycelium.  It has finite FIFO
  edges, deterministic fan-out, input/output Malli checks, and fixed :normal,
  :drop, or :reject edge regimes.  It does not yet implement dispatch
  predicates, joins, retries, timeouts, asynchronous handlers, or dynamic
  regime changes.  It compiles directly to jolt.sim.kernel; interactive
  control remains entirely owned by jolt.sim.session.  In particular, Session
  branch previews execute handlers, so a v1 handler must not perform I/O or
  mutate closed-over state; effectful application boundaries belong in the
  retained-worker lane or a later intent/commit split."
  (:require [jolt.sim.kernel :as kernel]
            [jolt.sim.schema :as schema]
            [jolt.sim.trace :as trace]))

(def ^:private regimes #{:normal :drop :reject})

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :type ::invalid-workflow))))

(defn- stable-ids [values]
  (vec (sort-by pr-str values)))

(defn- reachable-cells [starts edges]
  (let [targets (reduce (fn [result {:keys [from to]}]
                          (update result from (fnil conj []) to))
                        {}
                        edges)]
    (loop [frontier (vec (stable-ids starts))
           seen #{}]
      (if-let [cell-id (first frontier)]
        (if (contains? seen cell-id)
          (recur (subvec frontier 1) seen)
          (recur (into (subvec frontier 1) (get targets cell-id []))
                 (conj seen cell-id)))
        seen))))

(defn- acyclic? [cell-ids edges]
  (let [outgoing (reduce (fn [result {:keys [from to]}]
                           (update result from (fnil conj []) to))
                         {}
                         edges)
        indegree (reduce (fn [result {:keys [to]}]
                           (update result to inc))
                         (zipmap cell-ids (repeat 0))
                         edges)]
    (loop [ready (stable-ids
                  (filter #(zero? (get indegree %)) cell-ids))
           indegree indegree
           visited 0]
      (if-let [cell-id (first ready)]
        (let [[next-indegree newly-ready]
              (reduce (fn [[degrees ready-cells] target]
                        (let [degree (dec (get degrees target))]
                          [(assoc degrees target degree)
                           (if (zero? degree)
                             (conj ready-cells target)
                             ready-cells)]))
                      [indegree []]
                      (get outgoing cell-id []))]
          (recur (stable-ids (into (subvec ready 1) newly-ready))
                 next-indegree
                 (inc visited)))
        (= visited (count cell-ids))))))

(defn- closed-map? [value allowed required]
  (and (map? value)
       (every? #(contains? value %) required)
       (every? allowed (keys value))))

(defn- top-level-map-info! [spec-id direction form]
  (when-not (and (vector? form)
                 (= :map (first form))
                 (map? (second form))
                 (true? (:closed (second form))))
    (invalid! "Flow cell boundaries require a top-level closed Malli map"
              {:cell-spec spec-id :direction direction}))
  (let [entries (subvec form 2)
        keys (mapv first entries)
        required
        (reduce (fn [result entry]
                  (let [properties (when (= 3 (count entry)) (second entry))]
                    (if (true? (:optional properties))
                      result
                      (conj result (first entry)))))
                #{}
                entries)]
    {:keys (set keys) :required required}))

(defn- validate-cell-spec! [spec-id spec handlers resources]
  (when-not (keyword? spec-id)
    (invalid! "Cell specification IDs must be keywords" {:cell-spec spec-id}))
  (when-not (closed-map? spec #{:handler :schema :requires}
                         #{:handler :schema})
    (invalid! "A cell specification has an invalid shape"
              {:cell-spec spec-id :value spec}))
  (when-not (keyword? (:handler spec))
    (invalid! "A cell handler ID must be a keyword" {:cell-spec spec-id}))
  (when-not (fn? (get handlers (:handler spec)))
    (invalid! "A cell specification names no trusted handler"
              {:cell-spec spec-id :handler (:handler spec)}))
  (let [schema (:schema spec)]
    (when-not (and (map? schema)
                   (= #{:input :output} (set (keys schema))))
      (invalid! "A cell schema must contain exactly :input and :output"
                {:cell-spec spec-id :schema schema}))
    (doseq [[direction form] schema]
      (top-level-map-info! spec-id direction form)
      (schema/compile! {:kind :flow-cell
                        :pack-id spec-id
                        :field direction}
                       form)))
  (let [required (get spec :requires #{})]
    (when-not (and (set? required) (every? keyword? required))
      (invalid! "Cell resource requirements must be a set of keywords"
                {:cell-spec spec-id :requires required}))
    (when-let [missing (seq (remove #(contains? resources %) required))]
      (invalid! "A required workflow resource is missing"
                {:cell-spec spec-id :missing (stable-ids missing)}))))

(defn- normalize-cell! [alias cell specs]
  (when-not (keyword? alias)
    (invalid! "Workflow cell aliases must be keywords" {:cell alias}))
  (let [cell (if (keyword? cell) {:id cell} cell)]
    (when-not (closed-map? cell #{:id :params} #{:id})
      (invalid! "A workflow cell has an invalid shape"
                {:cell alias :value cell}))
    (when-not (contains? specs (:id cell))
      (invalid! "A workflow cell names an unknown cell specification"
                {:cell alias :cell-spec (:id cell)}))
    cell))

(defn- normalize-edge! [edge cells]
  (when-not (closed-map? edge
                         #{:id :from :to :capacity :regime}
                         #{:id :from :to :capacity})
    (invalid! "A workflow edge has an invalid shape" {:edge edge}))
  (let [{:keys [id from to capacity]} edge
        regime (get edge :regime :normal)]
    (when-not (keyword? id)
      (invalid! "Workflow edge IDs must be keywords" {:edge edge}))
    (when-not (contains? cells from)
      (invalid! "A workflow edge has an unknown source" {:edge id :from from}))
    (when-not (contains? cells to)
      (invalid! "A workflow edge has an unknown target" {:edge id :to to}))
    (when-not (and (integer? capacity) (pos? capacity))
      (invalid! "Workflow edge capacity must be a positive integer"
                {:edge id :capacity capacity}))
    (when-not (contains? regimes regime)
      (invalid! "A workflow edge has an unknown fixed regime"
                {:edge id :regime regime}))
    (assoc edge :regime regime)))

(defn- message [id data source]
  {:id id :data data :source source})

(defn- append-event [world event]
  (update world :events conj event))

(defn- link-ready? [world link-id]
  (seq (get-in world [:links link-id :queue])))

(defn- inputs-closed? [world cell-id incoming]
  (and (empty? (get-in world [:entries cell-id]))
       (every? #(get-in world [:links % :closed?]) incoming)))

(defn- ready-input [world cell-id incoming]
  (if-let [entry (first (get-in world [:entries cell-id]))]
    [:entry nil entry]
    (when-let [link-id (some #(when (link-ready? world %) %) incoming)]
      [:link link-id (first (get-in world [:links link-id :queue]))])))

(defn- consume-input [world cell-id [kind link-id msg]]
  (-> (if (= :entry kind)
        (update-in world [:entries cell-id] #(vec (rest %)))
        (update-in world [:links link-id :queue] #(vec (rest %))))
      (append-event {:op :message/received
                     :message-id (:id msg)
                     :cell cell-id
                     :edge link-id})))

(defn- blocked-consumer-cells [world current-cell target-ids]
  (->> target-ids
       distinct
       (remove #(= current-cell %))
       (filter #(= :blocked (get-in world [:cell-status %])))
       vec))

(defn- mark-status [world cell-id status woken-cells]
  (reduce #(assoc-in %1 [:cell-status %2] :runnable)
          (assoc-in world [:cell-status cell-id] status)
          woken-cells))

(defn- failure [reason detail]
  {:kind :jolt.sim.flow/transition-failure
   :type ::transition-failure
   :reason reason
   :detail detail})

(defn- route-output [world edge-ids data]
  (loop [world world
         remaining edge-ids
         touched []]
    (if-let [edge-id (first remaining)]
      (let [{:keys [to capacity regime queue closed?]}
            (get-in world [:links edge-id])
            message-id (:next-message-id world)
            msg (message message-id data edge-id)]
        (cond
          closed?
          {:failure (failure :closed-edge {:edge edge-id})}

          (= :reject regime)
          {:failure (failure :rejected-edge
                             {:edge edge-id :message-id message-id})}

          (= :drop regime)
          (recur (-> world
                     (update :next-message-id inc)
                     (append-event {:op :message/dropped
                                    :message-id message-id
                                    :edge edge-id
                                    :to to}))
                 (next remaining)
                 touched)

          (>= (count queue) capacity)
          {:failure (failure :edge-capacity
                             {:edge edge-id
                              :capacity capacity
                              :message-id message-id})}

          :else
          (recur (-> world
                     (update :next-message-id inc)
                     (update-in [:links edge-id :queue] conj msg)
                     (append-event {:op :message/enqueued
                                    :message-id message-id
                                    :edge edge-id
                                    :to to}))
                 (next remaining)
                 (conj touched to))))
      {:world world :touched touched})))

(defn- close-outputs [world edge-ids]
  (reduce (fn [{:keys [world touched]} edge-id]
            (let [to (get-in world [:links edge-id :to])]
              {:world (-> world
                          (assoc-in [:links edge-id :closed?] true)
                          (append-event {:op :edge/closed
                                         :edge edge-id
                                         :to to}))
               :touched (conj touched to)}))
          {:world world :touched []}
          edge-ids))

(defn- validate-data! [cell-id spec-id direction boundary data project?]
  (schema/validate! (:compiled boundary)
                    (if (and project? (map? data))
                      (select-keys data (:keys boundary))
                      data)
                    {:kind :flow-cell
                     :pack-id spec-id
                     :field direction}))

(defn- cell-site [cell-id phase]
  {:ns 'jolt.sim.flow :cell cell-id :phase phase})

(defn- invoke-cell [handler context state data cell-id spec-id
                    input-schema output-schema]
  (try
    (validate-data! cell-id spec-id :input input-schema data true)
    (let [result (handler context state data)]
      (when-not (and (map? result)
                     (= #{:state :data} (set (keys result))))
        (throw (ex-info "Cell handler returned an invalid shape"
                        {:type ::invalid-handler-result
                         :cell cell-id})))
      (trace/canonical-value (:state result)
                             [:flow :cell cell-id :state])
      (validate-data! cell-id spec-id :output output-schema (:data result)
                      false)
      (trace/canonical-value (:data result)
                             [:flow :cell cell-id :data])
      {:ok? true :result result})
    (catch :default error
      {:ok? false :error (trace/normalize-error error)})))

(defn- transition-step
  [specs handlers resources incoming outgoing task-id-map]
  (fn [{:keys [task world]} task-state]
    (let [cell-id (:cell task-state)
          spec (get specs (:cell-spec task-state))
          input (ready-input world cell-id (get incoming cell-id))]
      (if-not input
        (if (inputs-closed? world cell-id (get incoming cell-id))
          (let [{next-world :world touched :touched}
                (close-outputs world (get outgoing cell-id))
                woken-cells (blocked-consumer-cells next-world cell-id touched)
                wakes (mapv task-id-map woken-cells)
                next-world (mark-status next-world cell-id :completed
                                        woken-cells)]
            (-> (kernel/step-complete (:state task-state))
                (kernel/with-world next-world)
                (kernel/waking wakes)
                (kernel/at-site (cell-site cell-id :complete))))
          (-> (kernel/step-block task-state)
              (kernel/with-world (assoc-in world [:cell-status cell-id]
                                           :blocked))
              (kernel/at-site (cell-site cell-id :await))))
        (let [[_ _ msg] input
              data (:data msg)
              input-schema (get-in spec [:compiled-schema :input])
              output-schema (get-in spec [:compiled-schema :output])
              context (cond-> {:cell cell-id
                               :cell-spec (:cell-spec task-state)
                               :resources (select-keys
                                           resources
                                           (get spec :requires #{}))}
                        (contains? task-state :params)
                        (assoc :params (:params task-state)))
              invocation (invoke-cell (get handlers (:handler spec))
                                      context (:state task-state) data
                                      cell-id (:cell-spec task-state)
                                      input-schema output-schema)]
          (if-not (:ok? invocation)
            (-> (kernel/step-fail (:error invocation))
                (kernel/with-world
                 (assoc-in world [:cell-status cell-id] :failed))
                (kernel/at-site (cell-site cell-id :handle)))
            (let [handler-result (:result invocation)
                  next-cell-state (:state handler-result)
                  output-delta (:data handler-result)
                  accumulated (merge data output-delta)
                  _ (trace/canonical-value accumulated
                                           [:flow :cell cell-id :accumulated])
                  consumed-world (consume-input world cell-id input)
                  routed (route-output consumed-world (get outgoing cell-id)
                                       accumulated)]
              (if-let [error (:failure routed)]
                (-> (kernel/step-fail error)
                    ;; Routing is atomic across fan-out edges. Keep the
                    ;; original, unconsumed world and change only the mirrored
                    ;; cell status when any edge rejects the transition.
                    (kernel/with-world
                     (assoc-in world [:cell-status cell-id] :failed))
                    (kernel/at-site (cell-site cell-id :route)))
                (let [next-world (:world routed)
                  next-world (assoc-in next-world [:cell-state cell-id]
                                       next-cell-state)
                  exhausted? (inputs-closed? next-world cell-id
                                             (get incoming cell-id))
                  closed (when exhausted?
                           (close-outputs next-world (get outgoing cell-id)))
                  next-world (if closed (:world closed) next-world)
                  touched (into (:touched routed) (:touched closed))
                  woken-cells (blocked-consumer-cells next-world cell-id touched)
                  wakes (mapv task-id-map woken-cells)
                  next-status (cond
                                exhausted? :completed
                                (ready-input next-world cell-id
                                             (get incoming cell-id)) :runnable
                                :else :blocked)
                  next-world (mark-status next-world cell-id next-status
                                          woken-cells)
                  transition (if exhausted?
                               (kernel/step-complete accumulated)
                               (if (ready-input next-world cell-id
                                                (get incoming cell-id))
                                 (kernel/step-yield
                                  (assoc task-state :state next-cell-state))
                                 (kernel/step-block
                                  (assoc task-state :state next-cell-state))))]
                  (-> transition
                      (kernel/with-world next-world)
                      (kernel/waking wakes)
                      (kernel/at-site
                       (cell-site cell-id
                                  (if exhausted? :complete :handle)))))))))))))

(defn- validate-accumulated-paths! [entry-values cells specs edges]
  (let [targets (reduce (fn [result {:keys [from to]}]
                          (update result from (fnil conj []) to))
                        {}
                        (sort-by (comp pr-str :id) edges))
        initial (mapv (fn [{:keys [cell data]}]
                        {:cell cell
                         :available (set (keys data))
                         :path [cell]})
                      entry-values)]
    (loop [frontier initial
           seen #{}]
      (when-let [{:keys [cell available path] :as item} (first frontier)]
        (let [identity [cell available]
              remaining (subvec frontier 1)]
          (if (contains? seen identity)
            (recur remaining seen)
            (let [spec-id (:id (get cells cell))
                  input-boundary (get-in specs
                                         [spec-id :compiled-schema :input])
                  output-boundary (get-in specs
                                          [spec-id :compiled-schema :output])
                  missing (remove available (:required input-boundary))]
              (when (seq missing)
                (invalid! "A workflow path cannot satisfy a cell input"
                          {:cell cell
                           :path path
                           :missing-keys (stable-ids missing)}))
              ;; Only required output keys are guaranteed to exist on every
              ;; execution. Optional delta keys cannot satisfy a downstream
              ;; required input at compile time.
              (let [next-available (into available
                                         (:required output-boundary))
                    successors
                    (mapv (fn [target]
                            {:cell target
                             :available next-available
                             :path (conj path target)})
                          (get targets cell []))]
                (recur (into remaining successors)
                       (conj seen identity))))))))))

(defn compile-workflow
  "Compiles a finite accumulating-data workflow to a kernel config.

  Workflow shape:

    {:cells {:alias :cell-spec-or-{:id :cell-spec, optional :params data}}
     :edges [{:id :edge :from :alias :to :alias :capacity positive-int
              optional :regime (:normal, :drop, or :reject)}]
     :start :alias
     :input canonical-data
     :resources {:resource canonical-data ...}
     optional :max-steps non-negative-int}

  `:entries [{:cell alias :data data} ...]` may replace `:start` plus `:input`
  as a simulator-only finite batching seam.  It exists to exercise FIFO and
  capacity behavior in one explicit machine; an ordinary Mycelium-shaped run
  uses one :start/:input pair.

  Cell specs name trusted functions in `handlers`; functions never enter the
  canonical machine state. Flow boundary schemas are constrained to top-level
  closed Malli maps. Input validation projects the keys declared by a cell so
  prior accumulated keys remain available without causing a closed-map
  failure; output validates the exact handler delta. Stable integer task IDs
  are assigned by sorted cell alias, independent of map or edge declaration
  order.

  The surface is grounded in mycelium-clj/mycelium main at
  df45132fb5f70f46e6082ffe2b703efef4ce1a44 (2026-07-17).  Its :pipeline
  expansion, labeled :dispatches, joins, and transition-map edge shorthand are
  explicit follow-on gaps rather than silently different semantics."
  [workflow cell-specs handlers]
  (when-not (closed-map? workflow
                         #{:cells :edges :start :input :entries
                           :resources :max-steps}
                         #{:cells :edges :resources})
    (invalid! "Workflow manifest has an invalid shape" {:workflow workflow}))
  (when-not (map? cell-specs)
    (invalid! "Cell specification registry must be a map" {}))
  (when-not (map? handlers)
    (invalid! "Trusted handler table must be a map" {}))
  (let [cells (:cells workflow)
        resources (:resources workflow)]
    (when-not (and (map? cells) (seq cells))
      (invalid! "Workflow :cells must be a non-empty map" {:cells cells}))
    (when-not (map? resources)
      (invalid! "Workflow :resources must be a map" {:resources resources}))
    (when-not (vector? (:edges workflow))
      (invalid! "Workflow :edges must be a vector" {:edges (:edges workflow)}))
    (when (and (contains? workflow :max-steps)
               (not (and (integer? (:max-steps workflow))
                         (not (neg? (:max-steps workflow))))))
      (invalid! "Workflow :max-steps must be a non-negative integer"
                {:max-steps (:max-steps workflow)}))
    (trace/canonical-value workflow [:flow :workflow])
    (trace/canonical-value cell-specs [:flow :cell-specs])
    (let [cells (reduce-kv (fn [result alias cell]
                             (assoc result alias
                                    (normalize-cell! alias cell cell-specs)))
                           {}
                           cells)
          selected-spec-ids (stable-ids (set (map :id (vals cells))))
          compiled-specs
          (reduce
           (fn [result spec-id]
             (let [spec (get cell-specs spec-id)]
               (validate-cell-spec! spec-id spec handlers resources)
               (assoc result spec-id
                      (assoc spec :compiled-schema
                             (reduce-kv
                              (fn [schemas direction form]
                                (assoc schemas direction
                                       (assoc
                                        (top-level-map-info! spec-id direction
                                                             form)
                                        :compiled
                                        (schema/compile!
                                         {:kind :flow-cell
                                          :pack-id spec-id
                                          :field direction}
                                         form))))
                              {}
                              (:schema spec))))))
           {}
           selected-spec-ids)
          cell-ids (stable-ids (keys cells))
          task-id-map (zipmap cell-ids (range))
          edges (mapv #(normalize-edge! % cells) (:edges workflow))
          edge-ids (mapv :id edges)
          upstream-entry? (or (contains? workflow :start)
                              (contains? workflow :input))
          batch-entry? (contains? workflow :entries)]
      (when-not (= (count edge-ids) (count (set edge-ids)))
        (invalid! "Workflow edge IDs must be unique" {:edges edge-ids}))
      (when-not (acyclic? cell-ids edges)
        (invalid! "A finite Mycelium workflow must be acyclic" {}))
      (when-not (or (and upstream-entry?
                         (contains? workflow :start)
                         (contains? workflow :input)
                         (not batch-entry?))
                    (and batch-entry?
                         (not upstream-entry?)
                         (vector? (:entries workflow))
                         (seq (:entries workflow))))
        (invalid! "Use either :start/:input or a non-empty :entries vector"
                  {}))
      (let [entry-values (if batch-entry?
                           (:entries workflow)
                           [{:cell (:start workflow)
                             :data (:input workflow)}])]
        (doseq [entry entry-values]
          (when-not (and (closed-map? entry #{:cell :data} #{:cell :data})
                         (contains? cells (:cell entry)))
            (invalid! "A workflow entry has an invalid shape" {:entry entry})))
        (let [reachable (reachable-cells (map :cell entry-values) edges)
              unreachable (remove reachable cell-ids)]
          (when (seq unreachable)
            (invalid! "Every workflow cell must be reachable from an entry"
                      {:unreachable (stable-ids unreachable)})))
        (doseq [{:keys [cell data]} entry-values]
          (let [spec-id (:id (get cells cell))]
            (validate-data! cell spec-id :input
                            (get-in compiled-specs
                                    [spec-id :compiled-schema :input])
                            data true)))
        (validate-accumulated-paths! entry-values cells compiled-specs edges)
        (let [incoming (reduce (fn [result {:keys [id to]}]
                               (update result to conj id))
                             (zipmap cell-ids (repeat []))
                             (sort-by (comp pr-str :id) edges))
            outgoing (reduce (fn [result {:keys [id from]}]
                               (update result from conj id))
                             (zipmap cell-ids (repeat []))
                             (sort-by (comp pr-str :id) edges))
            initial-messages
            (map-indexed (fn [id {:keys [cell data]}]
                           [cell (message id data :workflow-entry)])
                         entry-values)
            entries (reduce (fn [result [cell msg]]
                              (update result cell conj msg))
                            (zipmap cell-ids (repeat []))
                            initial-messages)
            links (reduce (fn [result edge]
                            (assoc result (:id edge)
                                   (assoc edge :queue [] :closed? false)))
                          {}
                          edges)
            world {:task-ids task-id-map
                   :links links
                   :entries entries
                   :next-message-id (count initial-messages)
                   :events []
                   :cell-state (zipmap cell-ids (repeat nil))
                   :cell-status
                   (reduce (fn [result cell-id]
                             (assoc result cell-id
                                    (if (or (seq (get entries cell-id))
                                            (empty? (get incoming cell-id)))
                                      :runnable
                                      :blocked)))
                           {}
                           cell-ids)}
            tasks
            (reduce (fn [result cell-id]
                      (let [cell (get cells cell-id)
                            task-state (cond-> {:cell cell-id
                                               :cell-spec (:id cell)
                                               :state nil}
                                         (contains? cell :params)
                                         (assoc :params (:params cell)))
                            runnable? (or (seq (get entries cell-id))
                                          (empty? (get incoming cell-id)))]
                        (assoc result (get task-id-map cell-id)
                               ((if runnable? kernel/runnable kernel/blocked)
                                task-state))))
                    (sorted-map)
                    cell-ids)]
        {:tasks tasks
         :world world
         :max-steps (get workflow :max-steps 1000)
         :step (transition-step compiled-specs handlers resources incoming outgoing
                                task-id-map)})))))
