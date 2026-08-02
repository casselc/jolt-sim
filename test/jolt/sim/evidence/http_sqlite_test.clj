(ns jolt.sim.evidence.http-sqlite-test
  "Focused pure coverage for jolt.sim.evidence.http-sqlite's document shape
  validation and both post-hoc monitors. Deliberately independent of
  db/jolt-http: every case here works from small hand-authored literal
  evidence-v1 data (the real fault rule id, the real expected BLOB octets,
  and real statement SQL, mirrored from
  jolt.sim.http-sqlite-integration-test's own fixture data) rather than a
  live controlled run, so it runs on an ordinary Jolt image. The one real
  hermetic run, its evidence document, and canonical/EDN round trips remain
  in jolt.sim.http-sqlite-integration-test."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.evidence.http-sqlite :as evidence]))

(defn- caught [f]
  (try
    (f)
    ::no-throw
    (catch :default error
      error)))

(defn- caught-data [f]
  (ex-data (caught f)))

(defn- minimal-route [op-ordinal symbol route resource-id]
  {:id [:jolt.sim.evidence.http-sqlite/operation :foreign-function symbol
        op-ordinal]
   :kind :foreign-function
   :symbol symbol
   :resource-id resource-id
   :route route})

(defn- minimal-routes []
  [(minimal-route 1 "sqlite3_open" :handler [:sqlite-connection 1])
   (minimal-route 1 "sqlite3_prepare_v2" :handler [:sqlite-statement 1])
   (minimal-route 1 "sqlite3_finalize" :handler [:sqlite-statement 1])
   (minimal-route 1 "sqlite3_close_v2" :handler [:sqlite-connection 1])])

(defn- minimal-fault-history []
  [{:attempt-id [:jolt.sim.net.posix-fault/poll 1]
    :matches [{:rule-id :http-sqlite/interrupt-first-poll
               :match-ordinal 1 :activated? true :fired? true}]
    :firing {:rule-id :http-sqlite/interrupt-first-poll
             :match-ordinal 1 :firing-ordinal 1 :rule-firing-ordinal 1
             :outcome {:kind :captured-error :errno :eintr}}}
   {:attempt-id [:jolt.sim.net.posix-fault/poll 2]
    :matches [{:rule-id :http-sqlite/interrupt-first-poll
               :match-ordinal 2 :activated? false :fired? false}]
    :firing nil}])

(defn- minimal-evidence-doc []
  {:jolt.sim.evidence.http-sqlite/schema evidence/schema
   :jolt.sim.evidence.http-sqlite/version evidence/evidence-version
   :jolt.sim.evidence.http-sqlite/assumptions evidence/hermetic-assumptions
   :jolt.sim.evidence.http-sqlite/sqlite-sites
   [{:id [:jolt.sim.evidence.http-sqlite/site :sqlite-statement 1]
     :sql "create table sim_blob (id integer primary key, payload blob)"
     :param-count 0}]
   :jolt.sim.evidence.http-sqlite/ffi-routes (minimal-routes)
   :jolt.sim.evidence.http-sqlite/posix-fault
   {:snapshot {:next-attempt 3 :firings 1
               :rules [{:rule-id :http-sqlite/interrupt-first-poll
                        :on-match 1 :times 1 :matches 2 :firings 1}]}
    :history (minimal-fault-history)
    :attempts 2}
   :jolt.sim.evidence.http-sqlite/posix-capacity
   {:stream {:stream-capacity 1 :stream-would-blocks 1
             :stream-capacity-limited-writes 1 :max-stream-recv-bytes 1}
    :pipe {:pipe-capacity 1 :pipe-would-blocks 0 :max-pipe-fifo-bytes 1}}
   :jolt.sim.evidence.http-sqlite/sqlite-resources
   {:plan-index 1 :plan-count 1
    :open-connections 0 :open-statements 0 :clean? true}
   :jolt.sim.evidence.http-sqlite/posix-resources
   {:open-sockets 0 :open-pipes 0 :open-listeners 0 :open-addrinfo 0
    :waiter-count 0 :clean? true}
   :jolt.sim.evidence.http-sqlite/response
   {:status 200 :content-type "application/octet-stream"
    :content-length "5" :body-bytes [0 65 127 128 255]}})

(deftest http-sqlite-evidence-v1-canonical-replay
  (let [doc (minimal-evidence-doc)
        doc2 (minimal-evidence-doc)]
    ;; Rebuilding the same literal input twice is byte-identical -- no hidden
    ;; nondeterminism (host identity, arrival order, timestamps) leaked in.
    (is (= doc doc2))
    (is (= doc (evidence/validate-document! doc)))
    (is (= doc (edn/read-string (evidence/canonical-edn doc))))
    (is (= :pass (:status (evidence/check-handler-only-cleanup-safety doc))))
    (is (= :pass
           (:status (evidence/check-bounded-request-completes-after-retry
                     doc))))))

;; ---- safety monitor: negative cases ---------------------------------------

(deftest http-sqlite-evidence-v1-safety-monitor-negative-cases
  (let [doc (minimal-evidence-doc)]
    ;; A route that never reached a handler is the shortest possible offending
    ;; prefix: index 0.
    (let [corrupted (assoc-in doc
                              [:jolt.sim.evidence.http-sqlite/ffi-routes 0
                               :route]
                              :native)
          decision (evidence/check-handler-only-cleanup-safety corrupted)]
      (is (= :violation (:status decision)))
      (is (= 0 (:index decision)))
      (is (= :non-handler-route (:reason (:detail decision)))))

    ;; A second sqlite3_prepare_v2 while the first statement's lifecycle is
    ;; still open (no intervening finalize) is an overlapping resource
    ;; lifecycle, caught exactly at the second open call: index 2.
    (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
          overlapping
          (into (subvec routes 0 2)
                (into [(minimal-route 2 "sqlite3_prepare_v2" :handler
                                       [:sqlite-statement 2])]
                      (subvec routes 2)))
          corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                            overlapping)
          decision (evidence/check-handler-only-cleanup-safety corrupted)]
      (is (= :violation (:status decision)))
      (is (= 2 (:index decision)))
      (is (= :overlapping-resource-lifecycle (:reason (:detail decision)))))

    ;; A repeated sqlite3_finalize immediately after the statement is already
    ;; finalized has no active resource in its domain: narrowly
    ;; :terminal-without-open, caught at the repeated call: index 3.
    (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
          duplicated
          (into (subvec routes 0 3)
                (into [(minimal-route 2 "sqlite3_finalize" :handler
                                       [:sqlite-statement 1])]
                      (subvec routes 3)))
          corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                            duplicated)
          decision (evidence/check-handler-only-cleanup-safety corrupted)]
      (is (= :violation (:status decision)))
      (is (= 3 (:index decision)))
      (is (= :terminal-without-open (:reason (:detail decision)))))

    ;; A finalize call whose :resource-id names a different (never-opened)
    ;; ordinal than the domain's actual active resource is an exact id
    ;; mismatch, caught at the finalize call itself: index 2.
    (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
          mismatched (assoc-in routes [2 :resource-id] [:sqlite-statement 2])
          corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                            mismatched)
          decision (evidence/check-handler-only-cleanup-safety corrupted)]
      (is (= :violation (:status decision)))
      (is (= 2 (:index decision)))
      (is (= :terminal-id-mismatch (:reason (:detail decision)))))

    ;; After the statement domain's ordinal 1 fully opens and terminates,
    ;; reusing that same ordinal for a later open is a non-contiguous open
    ;; ordinal (expected 2, since one statement resource already opened in
    ;; this domain), caught at the reusing open call: index 4.
    (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
          reused (conj routes
                       (minimal-route 2 "sqlite3_prepare_v2" :handler
                                      [:sqlite-statement 1]))
          corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                            reused)
          decision (evidence/check-handler-only-cleanup-safety corrupted)]
      (is (= :violation (:status decision)))
      (is (= 4 (:index decision)))
      (is (= :non-contiguous-resource-open-ordinal
             (:reason (:detail decision)))))

    ;; A well-formed open/close pair followed by a second open that skips
    ;; ahead to ordinal 3 instead of the contiguous 2 is likewise a
    ;; non-contiguous open ordinal, caught at the skipping open call: index
    ;; 4. Contiguity subsumes the narrower "no reuse" case above.
    (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
          skipped (conj routes
                        (minimal-route 2 "sqlite3_prepare_v2" :handler
                                       [:sqlite-statement 3]))
          corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                            skipped)
          decision (evidence/check-handler-only-cleanup-safety corrupted)]
      (is (= :violation (:status decision)))
      (is (= 4 (:index decision)))
      (is (= :non-contiguous-resource-open-ordinal
             (:reason (:detail decision)))))))

(deftest http-sqlite-evidence-v1-retirement-and-coverage-negative-cases
  (let [doc (minimal-evidence-doc)]
    (testing "clean booleans cannot hide a live SQLite resource"
      (let [corrupted
            (assoc-in doc
                      [:jolt.sim.evidence.http-sqlite/sqlite-resources
                       :open-connections]
                      1)
            safety (evidence/check-handler-only-cleanup-safety corrupted)
            completion
            (evidence/check-bounded-request-completes-after-retry corrupted)]
        (is (= :violation (:status safety)))
        (is (= :sqlite-connections-open (:reason (:detail safety))))
        (is (= :violation (:status completion)))
        (is (= :sqlite-connections-open (:reason (:detail completion))))))

    (testing "the fixed scenario cannot pass without lifecycle evidence"
      (let [corrupted
            (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes [])
            decision (evidence/check-handler-only-cleanup-safety corrupted)]
        (is (= :violation (:status decision)))
        (is (= :no-sqlite-connection-lifecycle
               (:reason (:detail decision))))))

    (testing "statement sites must account for the consumed plan vector"
      (let [corrupted
            (assoc doc :jolt.sim.evidence.http-sqlite/sqlite-sites [])
            decision (evidence/check-handler-only-cleanup-safety corrupted)]
        (is (= :violation (:status decision)))
        (is (= :sqlite-site-count-mismatches-plan-count
               (:reason (:detail decision))))))

    (testing "each consumed plan and site requires its own statement lifecycle"
      (let [second-site
            {:id [:jolt.sim.evidence.http-sqlite/site :sqlite-statement 2]
             :sql "select payload from sim_blob where id = ?"
             :param-count 1}
            corrupted
            (-> doc
                (update :jolt.sim.evidence.http-sqlite/sqlite-sites
                        conj second-site)
                (assoc-in [:jolt.sim.evidence.http-sqlite/sqlite-resources
                           :plan-index]
                          2)
                (assoc-in [:jolt.sim.evidence.http-sqlite/sqlite-resources
                           :plan-count]
                          2))
            decision (evidence/check-handler-only-cleanup-safety corrupted)]
        (is (= :violation (:status decision)))
        (is (= :sqlite-statement-lifecycle-count-mismatch
               (:reason (:detail decision))))))))

;; ---- liveness monitor: negative cases --------------------------------------

(deftest http-sqlite-evidence-v1-liveness-monitor-negative-cases
  (let [doc (minimal-evidence-doc)]
    ;; The first poll attempt never actually fired the captured EINTR rule:
    ;; caught at history index 0.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :history 0
                     :firing]
                    nil)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 0 (:index decision)))
      (is (= :expected-first-poll-eintr-not-observed
             (:reason (:detail decision)))))

    ;; The first attempt fires, but the wrong rule: caught at index 0.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :history 0
                     :firing :rule-id]
                    :http-sqlite/other-rule)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 0 (:index decision)))
      (is (= :expected-first-poll-eintr-not-observed
             (:reason (:detail decision)))))

    ;; The first attempt fires the right rule but at the wrong match ordinal:
    ;; caught at index 0.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :history 0
                     :firing :match-ordinal]
                    2)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 0 (:index decision)))
      (is (= :expected-first-poll-eintr-not-observed
             (:reason (:detail decision)))))

    ;; The firing evidence is otherwise correct, but the first history entry's
    ;; own attempt-id is not the exact first poll attempt: caught at index 0.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :history 0
                     :attempt-id]
                    [:jolt.sim.net.posix-fault/poll 2])
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 0 (:index decision)))
      (is (= :expected-first-poll-eintr-not-observed
             (:reason (:detail decision)))))

    ;; The fault fired correctly at the first attempt, but the very next
    ;; attempt fired again instead of a plain delegated retry: caught at
    ;; history index 1.
    (let [refiring (assoc-in (minimal-fault-history) [1 :firing]
                              {:rule-id :http-sqlite/interrupt-first-poll
                               :match-ordinal 2 :firing-ordinal 2
                               :rule-firing-ordinal 2
                               :outcome {:kind :captured-error :errno :eintr}})
          corrupted (assoc-in doc
                              [:jolt.sim.evidence.http-sqlite/posix-fault
                               :history]
                              refiring)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 1 (:index decision)))
      (is (= :expected-delegated-retry-not-observed
             (:reason (:detail decision)))))

    ;; The retry's own attempt-id is not the exact immediate second poll
    ;; attempt: caught at index 1.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :history 1
                     :attempt-id]
                    [:jolt.sim.net.posix-fault/poll 3])
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 1 (:index decision)))
      (is (= :expected-delegated-retry-not-observed
             (:reason (:detail decision)))))

    ;; A well-formed fault -> retry sequence followed by a third history entry
    ;; that fires again violates "exactly one firing across all history":
    ;; caught at index 2.
    (let [refired-history
          (conj (minimal-fault-history)
                {:attempt-id [:jolt.sim.net.posix-fault/poll 3]
                 :matches [{:rule-id :http-sqlite/interrupt-first-poll
                            :match-ordinal 3 :activated? true :fired? true}]
                 :firing {:rule-id :http-sqlite/interrupt-first-poll
                          :match-ordinal 3 :firing-ordinal 2
                          :rule-firing-ordinal 2
                          :outcome {:kind :captured-error :errno :eintr}}})
          corrupted (assoc-in doc
                              [:jolt.sim.evidence.http-sqlite/posix-fault
                               :history]
                              refired-history)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 2 (:index decision)))
      (is (= :unexpected-additional-firing (:reason (:detail decision)))))

    ;; Fault-then-retry evidence is intact, but the response never completed:
    ;; only decidable once the whole history is folded, so :index is nil.
    (let [corrupted
          (assoc-in doc [:jolt.sim.evidence.http-sqlite/response :status] 500)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (nil? (:index decision)))
      (is (= :request-not-completed (:reason (:detail decision)))))

    ;; The recorded :attempts count disagrees with the actual history length:
    ;; only decidable once the whole history is folded, so :index is nil.
    (let [corrupted
          (assoc-in doc [:jolt.sim.evidence.http-sqlite/posix-fault :attempts]
                    3)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (nil? (:index decision)))
      (is (= :attempt-count-incoherent (:reason (:detail decision)))))

    ;; The cached fault snapshot's :firings disagrees with the actual number
    ;; of firings recorded in history: only decidable once the whole history
    ;; is folded, so :index is nil.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :snapshot
                     :firings]
                    2)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (nil? (:index decision)))
      (is (= :snapshot-firings-incoherent (:reason (:detail decision)))))

    ;; The named rule's own snapshot firing count disagrees with the actual
    ;; number of firings it produced in history: only decidable once the
    ;; whole history is folded, so :index is nil.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :snapshot
                     :rules 0 :firings]
                    2)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (nil? (:index decision)))
      (is (= :rule-firing-count-incoherent (:reason (:detail decision)))))

    ;; The named rule's own snapshot match count disagrees with the actual
    ;; history length (the rule matches every poll attempt): only decidable
    ;; once the whole history is folded, so :index is nil.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :snapshot
                     :rules 0 :matches]
                    1)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (nil? (:index decision)))
      (is (= :rule-match-count-incoherent (:reason (:detail decision)))))

    ;; A second rule in the snapshot means the fault plan is no longer the
    ;; one accepted singleton rule: only decidable once the whole history is
    ;; folded, so :index is nil.
    (let [corrupted
          (update-in doc
                     [:jolt.sim.evidence.http-sqlite/posix-fault :snapshot
                      :rules]
                     conj {:rule-id :http-sqlite/other-rule
                           :on-match 1 :times 1 :matches 0 :firings 0})
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (nil? (:index decision)))
      (is (= :expected-rule-not-singleton (:reason (:detail decision)))))

    ;; The one rule's own activation disagrees with the exact accepted
    ;; on-match/times contract: only decidable once the whole history is
    ;; folded, so :index is nil.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :snapshot
                     :rules 0 :times]
                    2)
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (nil? (:index decision)))
      (is (= :expected-rule-activation-incoherent (:reason (:detail decision)))))

    ;; A well-shaped first firing that captures the wrong errno is not a
    ;; document-shape exception -- validate-fault-outcome! only checks
    ;; #{:kind :errno} plus keyword types -- it is a monitor violation caught
    ;; at the shortest offending prefix, history index 0.
    (let [corrupted
          (assoc-in doc
                    [:jolt.sim.evidence.http-sqlite/posix-fault :history 0
                     :firing :outcome]
                    {:kind :captured-error :errno :eio})
          decision (evidence/check-bounded-request-completes-after-retry
                    corrupted)]
      (is (= :violation (:status decision)))
      (is (= 0 (:index decision)))
      (is (= :expected-first-poll-eintr-not-observed
             (:reason (:detail decision)))))))

;; ---- validate-document!: malformed nested-shape negative cases ------------

(deftest http-sqlite-evidence-v1-malformed-shape-negative-cases
  (let [doc (minimal-evidence-doc)]
    (testing "sqlite-site id ordinal disagrees with its own position"
      (let [corrupted
            (assoc-in doc
                      [:jolt.sim.evidence.http-sqlite/sqlite-sites 0 :id]
                      [:jolt.sim.evidence.http-sqlite/site :sqlite-statement 2])
            data (caught-data #(evidence/validate-document! corrupted))]
        (is (= evidence/invalid-document (:type data)))
        (is (= :invalid-sqlite-site-id (:reason data)))))

    (testing "ffi-route resource-id domain disagrees with its own symbol"
      (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
            corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                              (assoc-in routes [0 :resource-id]
                                        [:sqlite-statement 1]))
            data (caught-data #(evidence/validate-document! corrupted))]
        (is (= evidence/invalid-document (:type data)))
        (is (= :ffi-route-resource-id-domain-mismatch (:reason data)))))

    (testing "ffi-route :route is outside the closed enum"
      (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
            corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                              (assoc-in routes [0 :route] :bogus))
            data (caught-data #(evidence/validate-document! corrupted))]
        (is (= evidence/invalid-document (:type data)))
        (is (= :invalid-ffi-route-route (:reason data)))))

    (testing "fault firing outcome kind is not a keyword at all -- malformed shape"
      (let [corrupted
            (assoc-in doc
                      [:jolt.sim.evidence.http-sqlite/posix-fault :history 0
                       :firing :outcome]
                      {:kind "captured-error" :errno :eintr})
            data (caught-data #(evidence/validate-document! corrupted))]
        (is (= evidence/invalid-document (:type data)))
        (is (= :invalid-fault-outcome-type (:reason data)))))

    (testing "response body-bytes value is outside the unsigned byte range"
      (let [corrupted
            (assoc-in doc [:jolt.sim.evidence.http-sqlite/response :body-bytes]
                      [0 65 127 128 256])
            data (caught-data #(evidence/validate-document! corrupted))]
        (is (= evidence/invalid-document (:type data)))
        (is (= :invalid-response-body-bytes (:reason data)))))

    (testing "ffi-route operation ordinal is not contiguous with earlier encounters"
      (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
            corrupted (assoc doc :jolt.sim.evidence.http-sqlite/ffi-routes
                              (assoc-in routes [0 :id 3] 2))
            data (caught-data #(evidence/validate-document! corrupted))]
        (is (= evidence/invalid-document (:type data)))
        (is (= :invalid-ffi-route-id (:reason data)))))

    (testing "the monitors reject the same malformed document rather than throw uncontrolled"
      (let [corrupted
            (assoc-in doc [:jolt.sim.evidence.http-sqlite/response :body-bytes]
                      [0 65 127 128 256])]
        (is (= evidence/invalid-document
               (:type (caught-data
                       #(evidence/check-handler-only-cleanup-safety corrupted)))))
        (is (= evidence/invalid-document
               (:type (caught-data
                       #(evidence/check-bounded-request-completes-after-retry
                         corrupted)))))))))
