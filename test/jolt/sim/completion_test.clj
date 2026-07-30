(ns jolt.sim.completion-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.completion :as completion]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.monitor :as monitor]
            [jolt.sim.strategy :as strategy]
            [jolt.sim.trace :as trace]))

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(deftest pending-waiters-are-deduplicated-and-woken-in-task-order
  (let [registered (completion/register (completion/empty-registry) 7)
        first-wait (completion/register-waiter registered 7 3)
        second-wait (completion/register-waiter (:registry first-wait) 7 1)
        repeat-wait (completion/register-waiter (:registry second-wait) 7 3)
        terminal (completion/success {:reply :ok})
        published (completion/publish (:registry repeat-wait) 7 terminal)
        late (completion/register-waiter (:registry published) 7 9)]
    (is (false? (:ready? first-wait)))
    (is (false? (:ready? second-wait)))
    (is (false? (:ready? repeat-wait)))
    (is (nil? (completion/outcome (:registry repeat-wait) 7)))
    (is (true? (:won? published)))
    (is (= [1 3] (:wake published)))
    (is (= terminal (:outcome published)))
    (is (= #{} (get-in published [:registry 7 :waiters])))
    (is (true? (:ready? late)))
    (is (= terminal (:outcome late)))
    (is (= (:registry published) (:registry late)))))

(deftest first-publication-wins-completion-cancellation-races
  (let [pending (-> (completion/empty-registry)
                    (completion/register 1))
        success (completion/success :done)
        cancellation (completion/cancellation :deadline)
        success-first (completion/publish pending 1 success)
        cancel-loses
        (completion/publish (:registry success-first) 1 cancellation)
        cancel-first (completion/publish pending 1 cancellation)
        success-loses
        (completion/publish (:registry cancel-first) 1 success)]
    (testing "completion wins"
      (is (true? (:won? success-first)))
      (is (false? (:won? cancel-loses)))
      (is (= [] (:wake cancel-loses)))
      (is (= success (:outcome cancel-loses)))
      (is (= (:registry success-first) (:registry cancel-loses))))
    (testing "cancellation wins"
      (is (true? (:won? cancel-first)))
      (is (false? (:won? success-loses)))
      (is (= [] (:wake success-loses)))
      (is (= cancellation (:outcome success-loses)))
      (is (= (:registry cancel-first) (:registry success-loses))))))

(deftest failure-normalizes-host-exceptions-before-publication
  (let [raw (ex-info "operation failed" {:phase :read})
        terminal (completion/failure raw)
        registry (completion/register (completion/empty-registry) 2)
        published (completion/publish registry 2 terminal)
        printed (trace/canonical-edn published)]
    (is (= :failure (:kind terminal)))
    (is (= :jolt.sim/exception (get-in terminal [:error :kind])))
    (is (= "operation failed" (get-in terminal [:error :message])))
    (is (= {:phase :read} (get-in terminal [:error :data])))
    (is (= terminal (completion/outcome (:registry published) 2)))
    (is (not (string/includes? printed "#error")))))

(deftest malformed-state-and-inputs-fail-closed
  (let [valid (completion/register (completion/empty-registry) 0)
        invalid-registry
        (caught-data
         #(completion/outcome
           {0 {:waiters [] :outcome nil}}
           0))
        retained-waiters
        (caught-data
         #(completion/outcome
           (assoc-in
            (:registry
             (completion/publish
              valid 0 (completion/success :done)))
            [0 :waiters]
            #{1})
           0))
        invalid-operation
        (caught-data #(completion/register valid -1))
        invalid-task
        (caught-data #(completion/register-waiter valid 0 -1))
        duplicate
        (caught-data #(completion/register valid 0))
        unknown
        (caught-data #(completion/outcome valid 99))
        malformed-outcome
        (caught-data
         #(completion/publish valid 0 {:kind :success :value 1 :extra true}))
        raw-error-outcome
        (caught-data
         #(completion/publish
           valid
           0
           {:kind :failure :error (ex-info "raw" {})}))]
    (is (= ::completion/invalid-registry (:type invalid-registry)))
    (is (= :waiters-not-a-set (:reason invalid-registry)))
    (is (= ::completion/invalid-registry (:type retained-waiters)))
    (is (= :published-entry-retains-waiters (:reason retained-waiters)))
    (is (= ::completion/invalid-operation-id (:type invalid-operation)))
    (is (= ::completion/invalid-task-id (:type invalid-task)))
    (is (= ::completion/duplicate-operation (:type duplicate)))
    (is (= ::completion/unknown-operation (:type unknown)))
    (is (= ::completion/invalid-outcome (:type malformed-outcome)))
    (is (= trace/unsupported-value (:type raw-error-outcome)))))

(deftest byte-outcomes-have-stable-canonical-serialization
  (let [bytes (byte-array [0 255 128])
        terminal (completion/success bytes)
        registry (completion/register (completion/empty-registry) 4)
        first-result (completion/publish registry 4 terminal)
        second-result
        (completion/publish
         (completion/register (completion/empty-registry) 4)
         4
         (completion/success (byte-array [0 255 128])))
        first-edn (trace/canonical-edn first-result)]
    (is (= first-edn (trace/canonical-edn second-result)))
    (is (string/includes? first-edn ":jolt.sim.value/bytes"))
    (is (string/includes? first-edn "[0 255 128]"))))

(deftest published-byte-outcomes-are-immutable-snapshots
  (let [source (byte-array [1])
        terminal (completion/success source)
        _ (aset source 0 2)
        constructor-snapshot (vec (get terminal :value))
        registry (completion/register (completion/empty-registry) 5)
        published (completion/publish registry 5 terminal)
        published-value (get-in published [:outcome :value])
        publication-snapshot (vec published-value)
        before (trace/canonical-edn (:registry published))
        _ (aset (get terminal :value) 0 3)
        _ (aset published-value 0 4)
        first-read (get (completion/outcome (:registry published) 5) :value)
        _ (aset first-read 0 5)
        losing-publication
        (completion/publish
         (:registry published)
         5
         (completion/cancellation :late))
        losing-value (get-in losing-publication [:outcome :value])
        _ (aset losing-value 0 6)
        late-waiter
        (completion/register-waiter (:registry published) 5 9)
        late-value (get-in late-waiter [:outcome :value])
        _ (aset late-value 0 7)
        second-read (get (completion/outcome (:registry published) 5) :value)]
    (is (= [1] constructor-snapshot))
    (is (= [1] publication-snapshot))
    (is (= [1] (vec second-read)))
    (is (false? (:won? losing-publication)))
    (is (true? (:ready? late-waiter)))
    (is (= before (trace/canonical-edn (:registry published))))
    (is (not (bytes? (get-in published [:registry 5 :outcome :value]))))))

(deftest mutable-byte-keys-and-members-fail-closed
  (let [left (byte-array [7])
        map-error
        (caught-data
         #(completion/success
           (array-map left :left)))
        set-error
        (caught-data
         #(completion/cancellation #{[left]}))]
    (is (= trace/unsupported-value (:type map-error)))
    (is (= :mutable-map-key (:reason map-error)))
    (is (= trace/unsupported-value (:type set-error)))
    (is (= :mutable-set-member (:reason set-error)))))

(defn- completion-config [selection-strategy]
  {:tasks {0 (kernel/runnable :subscribe)
           1 (kernel/runnable :publish)}
   :world {:completions
           (completion/register (completion/empty-registry) 12)
           :observed nil}
   :strategy selection-strategy
   :step
   (fn [{:keys [task world]} state]
     (case [task state]
       [0 :subscribe]
       (let [subscription
             (completion/register-waiter (:completions world) 12 task)]
         (if (:ready? subscription)
           (-> (kernel/step-complete (:outcome subscription))
               (kernel/with-world
                (assoc world :observed (:outcome subscription)))
               (kernel/at-site :completion/late-observe))
           (-> (kernel/step-block :resume)
               (kernel/with-world
                (assoc world :completions (:registry subscription)))
               (kernel/at-site :completion/wait))))

       [0 :resume]
       (let [terminal (completion/outcome (:completions world) 12)]
         (-> (kernel/step-complete terminal)
             (kernel/with-world (assoc world :observed terminal))
             (kernel/at-site :completion/observe)))

       [1 :publish]
       (let [publication
             (completion/publish
              (:completions world)
              12
              (completion/success {:status 200 :body "ok"}))]
         (-> (kernel/step-complete :published)
             (kernel/with-world
              (assoc world :completions (:registry publication)))
             (kernel/waking (:wake publication))
             (kernel/at-site :completion/publish)))))})

(deftest completion-publication-wakes-a-real-simulated-consumer-and-replays
  (let [config (completion-config (strategy/scripted [0 1 0]))
        result (kernel/run config)
        grammar
        (monitor/check-trace-grammar (monitor/document (:trace result)))
        replayed
        (kernel/replay
         (completion-config (strategy/seeded 999))
         (:trace result))
        tags (set (map first (:trace result)))
        terminal (completion/success {:status 200 :body "ok"})]
    (is (= :completed (:status result)))
    (is (= terminal (get-in result [:world :observed])))
    (is (= terminal (get-in result [:tasks 0 :result])))
    (is (= :published (get-in result [:tasks 1 :result])))
    (is (= :pass (:status grammar)))
    (is (= result replayed))
    (is (= #{:run/initial
             :schedule/choose
             :task/transition
             :run/completed}
           tags))))
