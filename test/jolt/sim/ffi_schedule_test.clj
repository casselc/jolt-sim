(ns jolt.sim.ffi-schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.ffi-schedule :as ffi-schedule]))

;; The coordinator is exercised directly through its returned handler map and
;; API surface -- no run-controlled controller is installed. Concurrency uses
;; real futures (OS threads) plus promise/atom coordination; every blocking
;; call is joined through a bounded deref watchdog so a regression that would
;; otherwise hang surfaces as a named failure rather than a silent timeout.

(def ^:private a-key [:native-operation :a])
(def ^:private b-key [:native-operation :b])

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- ex-of [f]
  (try (f) ::not-thrown (catch :default e e)))

(defn- join [fut]
  ;; deref of a future that completed exceptionally rethrows, wrapped in an
  ;; ExecutionException whose cause is the callback failure. Return the
  ;; throwable itself so gate-aborted? can unwrap the cause chain; a genuine
  ;; timeout still surfaces as ::timeout.
  (try (deref fut 5000 ::timeout)
       (catch :default e e)))

;; A sleep-free busy poll on the coordinator's own atom-backed diagnostics. The
;; atom read -- not any elapsed time -- is the correctness witness; the deadline
;; only bounds how long a regression may spin before the test reports it.
(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/yield) (recur))
        :else false))))

(defn- release-order [coord]
  (mapv second (filter #(= :release (first %)) ((:evidence coord)))))

(defn- arrive-order [coord]
  (:arrival-order ((:diagnostics coord))))

(defn- complete-order [coord]
  (:completion-order ((:diagnostics coord))))

;; ---- plan shape / config rejection --------------------------------------

(deftest malformed-plan-and-missing-keys-are-rejected-at-construction
  (let [good {a-key (fn [_] :a) b-key (fn [_] :b)}
        cases [[nil :plan-must-be-a-nonempty-vector]
               [[] :plan-must-be-a-nonempty-vector]
               ['(0 1) :plan-must-be-a-nonempty-vector]
               [[{}] :malformed-step]
               [[42] :malformed-step]
               [[{:handler-key a-key :occurrence 1}] :malformed-step]       ; missing :id
               [[{:id ::a :occurrence 1}] :malformed-step]                  ; missing :handler-key
               [[{:id ::a :handler-key a-key}] :malformed-step]             ; missing :occurrence
               [[{:id :nonnamespaced :handler-key a-key :occurrence 1}]
                :malformed-step]                                            ; bare keyword id
               [[{:id ::a :handler-key a-key :occurrence 0}] :malformed-step] ; non-positive occurrence
               [[{:id ::a :handler-key a-key :occurrence 1 :extra 9}]
                :malformed-step]                                            ; not a closed map
               [[{:id ::a :handler-key a-key :occurrence 1}
                 {:id ::a :handler-key b-key :occurrence 1}]
                :duplicate-ids]
               [[{:id ::a :handler-key a-key :occurrence 1}
                 {:id ::b :handler-key a-key :occurrence 1}]
                :duplicate-selectors]
               [[{:id ::a :handler-key [:native-operation :missing] :occurrence 1}]
                :missing-handler-key]]]
    (doseq [[plan reason] cases]
      (let [data (ex-data-of #(ffi-schedule/coordinator good plan))]
        (is (some? data) {:plan plan})
        (is (= ffi-schedule/invalid-plan (:type data)) {:plan plan})
        (is (= reason (:reason data)) {:plan plan}))))
  (let [data (ex-data-of #(ffi-schedule/coordinator :not-a-map
                                                   [{:id ::a :handler-key a-key :occurrence 1}]))]
    (is (= ffi-schedule/invalid-plan (:type data)))
    (is (= :handlers-must-be-a-map (:reason data))))
  (is (true? (ffi-schedule/valid-plan? [{:id ::a :handler-key a-key :occurrence 1}]))))

;; ---- unselected pass-through --------------------------------------------

(deftest unselected-arrival-and-unwrapped-key-delegate-unchanged
  (let [calls (atom 0)
        handlers {a-key (fn [d] (swap! calls inc) [:orig d])
                  b-key (fn [d] [:untouched d])}
        coord (ffi-schedule/coordinator
               handlers
               [{:id ::a :handler-key a-key :occurrence 1}])
        wrapped (:handlers coord)
        diag (:diagnostics coord)]
    ;; occurrence 1 of a-key is selected; occurrences 2 and 3 are not.
    (is (= [:orig {:n 1}] ((wrapped a-key) {:n 1})))
    (is (= [:orig {:n 2}] ((wrapped a-key) {:n 2})))
    (is (= [:orig {:n 3}] ((wrapped a-key) {:n 3})))
    ;; The original handler ran for all three calls (selected + delegated).
    (is (= 3 @calls))
    ;; Only the selected occurrence registered an arrival; delegations leave no
    ;; schedule trace.
    (is (= #{::a} (:arrived (diag))))
    (is (= [[:release ::a]] ((:evidence coord))))
    (is (= [::a] (arrive-order coord)))
    (is (= [::a] (complete-order coord)))
    ;; A key not named by the plan is the exact original function value.
    (is (identical? (get handlers b-key) (get wrapped b-key)))
    (is (= [:untouched {:x 9}] ((wrapped b-key) {:x 9})))))

;; ---- release order: discriminate both plan orders -----------------------

(deftest release-follows-plan-order-when-the-later-step-arrives-first
  ;; Plan order is [a, b]; b is forced to arrive and block before a is driven.
  ;; Release must still be [a, b], proving release is plan-driven, not a
  ;; reflection of arrival order. Requires a real concurrent call: b is parked
  ;; at its gate on another thread while a advances the frontier from this one.
  (let [handlers {a-key (fn [_] :a) b-key (fn [_] :b)}
        coord (ffi-schedule/coordinator
               handlers
               [{:id ::a :handler-key a-key :occurrence 1}
                {:id ::b :handler-key b-key :occurrence 1}])
        wrapped (:handlers coord)
        diag (:diagnostics coord)
        b-fut (future ((wrapped b-key) {}))]
    (is (true? (wait-until (fn []
                             (let [d (diag)]
                               (and (contains? (:arrived d) ::b)
                                    (not (contains? (:released d) ::b))
                                    (pos? (:in-flight d)))))
                           3000))
        "b should arrive and park at its gate before a is driven")
    (is (= :a ((wrapped a-key) {})))
    (is (= :b (join b-fut)))
    (is (= [::b ::a] (arrive-order coord))
        "arrival order is b then a")
    (is (= [::a ::b] (release-order coord))
        "release order follows the plan, not the arrival order")))

(deftest release-follows-plan-order-for-the-reversed-plan
  ;; The same arrival pattern (later step arrives first) under plan [b, a]
  ;; releases in [b, a]: both plan orders are demonstrable and neither mirrors
  ;; the arrival order.
  (let [handlers {a-key (fn [_] :a) b-key (fn [_] :b)}
        coord (ffi-schedule/coordinator
               handlers
               [{:id ::b :handler-key b-key :occurrence 1}
                {:id ::a :handler-key a-key :occurrence 1}])
        wrapped (:handlers coord)
        diag (:diagnostics coord)
        a-fut (future ((wrapped a-key) {}))]
    (is (true? (wait-until (fn []
                             (let [d (diag)]
                               (and (contains? (:arrived d) ::a)
                                    (not (contains? (:released d) ::a))
                                    (pos? (:in-flight d)))))
                           3000)))
    (is (= :b ((wrapped b-key) {})))
    (is (= :a (join a-fut)))
    (is (= [::a ::b] (arrive-order coord)))
    (is (= [::b ::a] (release-order coord)))))

;; ---- release admission does not await handler completion ----------------

(deftest later-step-release-does-not-await-earlier-handler-completion
  ;; This is a coordinator unit witness, not an end-to-end poll race. A modeled
  ;; poll invokes a selected write while its own handler is still running.
  ;; Releasing on arrival -- not on handler completion -- is the only reason the
  ;; nested write's gate is delivered, so the pair does not deadlock. It proves
  ;; admission semantics but makes no claim about ordinary cross-thread handler
  ;; execution order. A watchdog turns a regression into a reported timeout.
  (let [poll-key [:native-operation :poll]
        write-key [:native-operation :write]
        write-call (atom nil)
        coord-cell (atom nil)
        write-released-before-poll-complete (promise)
        handlers {poll-key (fn [_]
                             ;; The modeled poll performs its write while running.
                             (let [_ (@write-call {:arg 7})]
                               (let [d ((:diagnostics @coord-cell))
                                     released (:released d)
                                     completed (:completed d)]
                                 (when (and (contains? released ::write)
                                            (not (contains? completed ::poll)))
                                   (deliver write-released-before-poll-complete true)))
                               :poll-done))
                  write-key (fn [d] (:arg d))}
        coord (ffi-schedule/coordinator
               handlers
               [{:id ::poll :handler-key poll-key :occurrence 1}
                {:id ::write :handler-key write-key :occurrence 1}])
        wrapped (:handlers coord)]
    (reset! coord-cell coord)
    (reset! write-call (wrapped write-key))
    (let [outcome (join (future ((wrapped poll-key) {})))]
      (is (not= ::timeout outcome) "the nested boundary pair must not deadlock")
      (is (= :poll-done outcome)))
    ;; The proof promise was delivered from inside poll's handler, after write
    ;; returned, while poll had not yet completed.
    (is (true? (deref write-released-before-poll-complete 1000 false)))
    (is (= [::poll ::write] (release-order coord)))
    ;; write completed before poll: the entire write handler ran on poll's stack.
    (is (= [::write ::poll] (complete-order coord)))))

;; ---- handler exception preservation -------------------------------------

(deftest handler-exception-is-preserved-and-in-flight-drains
  (let [boom (ex-info "boom" {:why :because})
        coord (ffi-schedule/coordinator
               {a-key (fn [_] (throw boom))}
               [{:id ::a :handler-key a-key :occurrence 1}])
        wrapped (:handlers coord)
        diag (:diagnostics coord)
        thrown (ex-of #((wrapped a-key) {}))]
    ;; The exact application exception propagates: not wrapped, not retained as
    ;; a schedule failure, not swallowed by the in-flight accounting.
    (is (identical? boom thrown))
    (is (zero? (:in-flight (diag))))
    (is (nil? ((:failure coord))))
    ;; The step did not complete; the completeness check reports it missing.
    (let [data (ex-data-of (:check-complete! coord))]
      (is (= ffi-schedule/incomplete-schedule (:type data)))
      (is (= ::a (first (:missing data)))))))

;; ---- missing step / completeness ----------------------------------------

(deftest missing-step-fails-the-completeness-check
  (let [coord (ffi-schedule/coordinator
               {a-key (fn [_] :a) b-key (fn [_] :b)}
               [{:id ::a :handler-key a-key :occurrence 1}
                {:id ::b :handler-key b-key :occurrence 1}])
        wrapped (:handlers coord)]
    ;; Only the first planned step ever arrives; ::b is never driven.
    (is (= :a ((wrapped a-key) {})))
    (let [data (ex-data-of (:check-complete! coord))]
      (is (= ffi-schedule/incomplete-schedule (:type data)))
      (is (= :incomplete (:reason data)))
      (is (= [::b] (:missing data))))))

(deftest premature-completeness-check-aborts-and-drains-a-blocked-waiter
  (let [coord (ffi-schedule/coordinator
               {a-key (fn [_] :a) b-key (fn [_] :b)}
               [{:id ::a :handler-key a-key :occurrence 1}
                {:id ::b :handler-key b-key :occurrence 1}])
        wrapped (:handlers coord)
        diag (:diagnostics coord)
        ;; The later step arrives first and waits for ::a's admission.
        b-fut (future ((wrapped b-key) {}))]
    (is (true? (wait-until (fn []
                             (let [d (diag)]
                               (and (= #{::b} (:arrived d))
                                    (empty? (:released d))
                                    (= 1 (:in-flight d)))))
                           3000)))
    ;; Completeness is intentionally fail-closed: checking before caller-owned
    ;; workers are quiescent aborts the schedule and drains the parked waiter.
    (let [data (ex-data-of (:check-complete! coord))]
      (is (= ffi-schedule/incomplete-schedule (:type data)))
      (is (= :calls-in-flight (:reason data)))
      (is (= [::a ::b] (:missing data))))
    (let [outcome (join b-fut)]
      (is (not= ::timeout outcome))
      (is (true? (ffi-schedule/gate-aborted? outcome))))
    (is (true? (wait-until #(zero? (:in-flight (diag))) 3000)))
    (is (true? (:aborted? (diag))))
    (is (= ffi-schedule/incomplete-schedule
           (:type (ex-data ((:failure coord))))))))

(deftest completeness-check-passes-when-every-step-completes
  (let [coord (ffi-schedule/coordinator
               {a-key (fn [_] :a) b-key (fn [_] :b)}
               [{:id ::a :handler-key a-key :occurrence 1}
                {:id ::b :handler-key b-key :occurrence 1}])
        wrapped (:handlers coord)]
    (is (= :a ((wrapped a-key) {})))
    (is (= :b ((wrapped b-key) {})))
    (is (nil? ((:check-complete! coord))))))

;; ---- abort drainage -----------------------------------------------------

(deftest abort-drains-blocked-gates-and-waiters-throw-typed-abort
  (let [coord (ffi-schedule/coordinator
               {a-key (fn [_] :a) b-key (fn [_] :b)}
               [{:id ::a :handler-key a-key :occurrence 1}
                {:id ::b :handler-key b-key :occurrence 1}])
        wrapped (:handlers coord)
        diag (:diagnostics coord)
        ;; b is the later step; drive it first so it parks at its gate.
        b-fut (future ((wrapped b-key) {}))]
    (is (true? (wait-until (fn []
                             (let [d (diag)]
                               (and (contains? (:arrived d) ::b)
                                    (not (contains? (:released d) ::b))
                                    (pos? (:in-flight d)))))
                           3000)))
    ((:abort! coord))
    ;; The blocked waiter wakes and throws the typed internal gate-aborted
    ;; error, transported through the future as its cause chain.
    (let [outcome (join b-fut)]
      (is (not= ::timeout outcome))
      (is (true? (ffi-schedule/gate-aborted? outcome))))
    ;; Every blocked gate drained; in-flight returns to zero even though the
    ;; waiter unwound by throwing.
    (is (true? (wait-until #(zero? (:in-flight (diag))) 3000)))
    (is (zero? (:in-flight (diag))))
    (is (true? (:aborted? (diag))))
    ;; After abort the schedule cannot be made complete.
    (let [data (ex-data-of (:check-complete! coord))]
      (is (= ffi-schedule/incomplete-schedule (:type data)))
      (is (true? (:aborted? data))))))

(deftest post-abort-selected-arrival-fails-closed
  (let [coord (ffi-schedule/coordinator
               {a-key (fn [_] :a)}
               [{:id ::a :handler-key a-key :occurrence 1}])
        wrapped (:handlers coord)]
    ((:abort! coord))
    (let [data (ex-data-of #((wrapped a-key) {}))]
      (is (= ffi-schedule/aborted-arrival (:type data)))
      (is (= ::a (:id data))))
    ;; The first post-abort violation is retained as the schedule failure.
    (let [failure ((:failure coord))]
      (is (some? failure))
      (is (= ffi-schedule/aborted-arrival (:type (ex-data failure)))))))
