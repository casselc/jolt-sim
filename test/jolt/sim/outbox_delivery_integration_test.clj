(ns jolt.sim.outbox-delivery-integration-test
  "Focused real/hermetic parity gate for the first ordinary whole-application
   post-COMMIT outbox delivery witness."
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-delivery :as fixture]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

;; Bound by -main to the closed reopen config map
;; {:case-dir :bare-filename :real-spec :hermetic-spec :command} so the
;; close/reopen deftest can run real and hermetic lanes over the same ordinary
;; semantics with a unique file-backed case directory. Defaults to nil; the
;; reopen gate is run only through -main, which always binds it.
(def ^:dynamic *reopen-config* nil)

(def ^:private expected-row
  {:outbox-id 1
   :request-id "req-1"
   :entity-id "entity-a"
   :version 1
   :payload [0 127 128 255]
   :status :pending})

(def ^:private expected-result
  {:status :committed
   :request-id "req-1"
   :entity-id "entity-a"
   :version 1
   :outbox-id 1})

(def ^:private expected-state
  {:entities {"entity-a" {:version 1 :payload [0 127 128 255]}}
   :request-log
   {"req-1" {:command fixture/default-command
             :result expected-result}}
   :next-outbox-id 2
   :outbox [expected-row]})

;; The delivered side of the boundary: the same row and state after the
;; validated ack authorizes the guarded pending -> delivered marking.
(def ^:private expected-delivered-row
  (assoc expected-row :status :delivered))

(def ^:private expected-delivered-state
  (assoc expected-state :outbox [expected-delivered-row]))

(def ^:private expected-marking
  {:row expected-delivered-row :changed? true})

(def ^:private expected-identities
  {:request-id "req-1"
   :transaction-id [:outbox/command "req-1"]
   :outbox-id 1
   :delivery-id [:outbox/delivery 1]
   :attempt-id [:outbox/delivery-attempt 1 1]})

(def ^:private expected-message
  {"type" "outbox_delivery"
   "outbox-id" 1
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1
   "payload" [0 127 128 255]
   "attempt" 1})

(def ^:private expected-ack
  {"type" "outbox_delivery_ok"
   "outbox-id" 1
   "attempt" 1})

(def ^:private expected-http-response
  {"type" "command_ok"
   "status" "committed"
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1
   "outbox-id" 1})

(def ^:private modeled-outbox-key
  [:jolt.sim.sqlite/row
   :outbox/rows
   [{:type :integer :value 1}]])

(defn- hostile-ack [message]
  (assoc expected-ack
         "outbox-id" (get message "outbox-id")
         "attempt" 99))

(deftest ordinary-http-commits-outbox-then-delivers-over-framed-tcp
  (let [real-result (when-not *sim-only?*
                      (fixture/exercise-outbox-delivery))
        mem (memory/world)
        sqlite-world (sqlite/world mem (plans/delivery-statement-plans))
        posix-world (posix/world mem (net/target-descriptor)
                                 {:progress-limit 64
                                  :stream-capacity 8
                                  :pipe-capacity 1})
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
        controlled
        (runtime/run-controlled
         {:ffi-handlers handlers
          :drain-timeout-ms 10000}
         fixture/exercise-outbox-delivery)
        result (:result controlled)
        app (:application result)
        delivery (:delivery app)]
    (testing "the unchanged ordinary application has exact real/hermetic parity"
      (when-not *sim-only?*
        (is (= real-result result))))

    (testing "the HTTP command exposes exactly one post-COMMIT pending row"
      (is (= expected-identities (:identities app)))
      (is (= {:value fixture/default-command
              :result expected-result
              :emitted [expected-row]}
             (:command app)))
      ;; The explicit post-COMMIT/pre-delivery reload: the emitted command row
      ;; remains :pending evidence on this side of the delivery boundary.
      (is (= expected-state (:pending-state app)))
      (is (= :pending (get-in app [:pending-state :outbox 0 :status]))))

    (testing "the validated ack gates one durable delivery marking"
      ;; Exactly the mark step without its duplicate :state.
      (is (= expected-marking (:marking app)))
      ;; The final delivered state equals the reload after mark-delivered!:
      ;; the target row is :delivered and no :pending rows remain.
      (is (= expected-delivered-state (:store-state app)))
      (is (= :delivered (get-in app [:store-state :outbox 0 :status])))
      (is (empty? (filter #(= :pending (:status %))
                          (get-in app [:store-state :outbox])))))

    (testing "a later phase delivers the reloaded row in one framed bencode attempt"
      (is (= [expected-message] (:requests delivery)))
      (is (= [expected-ack] (:replies delivery)))
      (is (pos? (:sent-bytes delivery)))
      (is (pos? (:received-bytes delivery)))
      (is (= {:connection [true false]} (:close-results delivery))))

    (testing "the receiver observes the same semantic delivery and acknowledges it"
      (is (= [expected-message] (get-in result [:receiver :requests])))
      (is (empty? (get-in result [:receiver :server-errors]))))

    (testing "the HTTP response reports the committed command result byte-exactly"
      (is (= 200 (get-in result [:http :status])))
      (is (= "application/x-bencode"
             (get-in result [:http :content-type])))
      (is (= expected-http-response (get-in result [:http :response])))
      (is (empty? (get-in result [:http :server-errors])))
      (is (= {:connection [true false]}
             (get-in result [:http :close-results]))))

    (testing "all 24 SQLite plans and all native effects stay inside the model"
      (is (= {:plan-index 24
              :plan-count 24
              :open-dbs 0
              :active-stmts 0}
             (sqlite/summary sqlite-world)))
      (is (every? #(= :handler (:route %)) (:effect-trace controlled))))

    (testing "finite model capacities exercise bounded partial progress"
      (let [stream (posix/capacity-summary posix-world)
            pipe (posix/pipe-capacity-summary posix-world)]
        ;; The stream bound is smaller than the native progress ceiling, so
        ;; capacity itself must split writes without coupling this smoke gate to
        ;; the extreme one- and two-byte fragmentation reserved for a future
        ;; fragmentation lane.
        (is (= 8 (:stream-capacity stream)))
        (is (pos? (:stream-capacity-limited-writes stream)))
        (is (= 8 (:max-stream-recv-bytes stream)))
        ;; Would-block counts depend on host thread timing. Occupancy proves
        ;; that the finite self-pipe bound participated without asserting an
        ;; exact schedule-sensitive retry count.
        (is (= 1 (:pipe-capacity pipe)))
        (is (= 1 (:max-pipe-fifo-bytes pipe)))))

    (testing "the shared native worlds quiesce cleanly"
      (is (true? (memory/clean? mem)))
      (is (true? (sqlite/clean? sqlite-world)))
      (is (true? (posix/clean? posix-world))))))

(deftest hostile-ack-cannot-authorize-delivery-marking
  ;; This is the negative control for the slice's central ordering claim. The
  ;; complete ordinary HTTP -> SQLite -> TCP path succeeds through the remote
  ;; reply, but the receiver deliberately corrupts only the correlated attempt
  ;; number. Ack validation must throw before the first mark-delivered! SQL
  ;; statement is claimed, and closing the in-memory connection must preserve
  ;; the committed pending row as model evidence.
  (let [mem (memory/world)
        sqlite-world (sqlite/world mem (plans/delivery-statement-plans))
        posix-world (posix/world mem (net/target-descriptor)
                                 {:progress-limit 64
                                  :stream-capacity 8
                                  :pipe-capacity 1})
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
        error
        (try
          (runtime/run-controlled
           {:ffi-handlers handlers
            :drain-timeout-ms 10000}
           (fn []
             (fixture/exercise-outbox-delivery
              fixture/default-command
              hostile-ack)))
          nil
          (catch :default e e))
        sqlite-state (sqlite/state sqlite-world)
        closed-db (first (:closed-db-evidence sqlite-state))
        row (get-in closed-db [:committed modeled-outbox-key])]
    (testing "the exact hostile acknowledgement is rejected"
      (is (some? error))
      (is (= :jolt.sim.fixtures.outbox-delivery/invalid-flow
             (:type (ex-data error))))
      (is (= :ack-mismatch (:reason (ex-data error))))
      (is (= [expected-ack]
             (get-in (ex-data error) [:detail :expected])))
      (is (= [(assoc expected-ack "attempt" 99)]
             (get-in (ex-data error) [:detail :actual]))))

    (testing "no mark transaction is claimed and the row stays pending"
      ;; Plans 0..14 are schema, command transaction, and the explicit
      ;; post-COMMIT reload. Plan 15 is the unclaimed BEGIN belonging to
      ;; mark-delivered!; all nine mark/final-reload plans remain untouched.
      (is (= {:plan-index 15
              :plan-count 24
              :open-dbs 0
              :active-stmts 0}
             (sqlite/summary sqlite-world)))
      (is (= {:type :text :value "pending"} (get row "status")))
      (is (not-any? #(= :update-row (:op %)) (:row-evidence closed-db))))

    (testing "the failed whole-app case still quiesces every modeled resource"
      (is (true? (memory/clean? mem)))
      (is (true? (sqlite/clean? sqlite-world)))
      (is (true? (posix/clean? posix-world))))))

(deftest sqlite-close-failure-cannot-replace-primary-ack-failure
  ;; A close failure used to escape with-open's finally and replace the hostile
  ;; ack mismatch entirely. Wrap one real modeled connection so physical close
  ;; succeeds and its owner then reports a deterministic cleanup failure. The
  ;; application error must remain primary, with bounded close diagnostics.
  (let [mem (memory/world)
        sqlite-world (sqlite/world mem (plans/delivery-statement-plans))
        posix-world (posix/world mem (net/target-descriptor)
                                 {:progress-limit 64
                                  :stream-capacity 8
                                  :pipe-capacity 1})
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
        real-connection jdbc/connection
        close-error (ex-info "injected sqlite close failure"
                             {:kind :close-control})
        error
        (with-redefs-fn
          {#'jdbc/connection
           (fn [spec]
             (let [conn (real-connection spec)
                   close (:close conn)]
               (assoc conn :close
                      (fn []
                        (close)
                        (throw close-error)))))}
          #(try
             (runtime/run-controlled
              {:ffi-handlers handlers
               :drain-timeout-ms 10000}
              (fn []
                (fixture/exercise-outbox-delivery
                 fixture/default-command
                 hostile-ack)))
             nil
             (catch :default e e)))
        data (ex-data error)
        cleanup (:outbox-delivery/cleanup-errors data)]
    (testing "the acknowledgement mismatch remains the primary failure"
      (is (some? error))
      (is (= :ack-mismatch (:reason data)))
      (is (= :ack-mismatch (:reason (ex-data (ex-cause error)))))
      (is (= ":ack-mismatch"
             (get-in data
                     [:outbox-delivery/primary-error :data ":reason"]))))

    (testing "the close failure is retained as bounded cleanup evidence"
      (is (= [:sqlite-connection-close] (mapv :operation cleanup)))
      (is (= ["injected sqlite close failure"] (mapv :message cleanup))))

    (testing "reported close failure still leaves no native/model owner live"
      (is (= {:plan-index 15
              :plan-count 24
              :open-dbs 0
              :active-stmts 0}
             (sqlite/summary sqlite-world)))
      (is (true? (memory/clean? mem)))
      (is (true? (sqlite/clean? sqlite-world)))
      (is (true? (posix/clean? posix-world))))))

(deftest ordinary-http-commits-then-close-reopen-delivers-over-framed-tcp
  ;; The close/reopen witness: connection 0 commits the pending row over the
  ;; ordinary HTTP -> SQLite path and closes; only after that clean close does
  ;; a freshly reopened connection 1 reload the committed row, deliver it over
  ;; the existing framed TCP/bencode path, validate the correlated ack, run
  ;; the guarded durable marking, and reload. This proves the committed image
  ;; survives a clean sequential close/reopen of one file-backed SQLite
  ;; database, not crash durability, locking, multi-connection concurrency, or
  ;; exactly-once delivery.
  (let [{:keys [real-spec hermetic-spec bare-filename command]} *reopen-config*
        real-result (when-not *sim-only?*
                      (fixture/exercise-outbox-delivery-reopen real-spec command))
        mem (memory/world)
        sqlite-world (sqlite/world mem (plans/reopen-delivery-statement-plans)
                                   {:persistent-filenames #{bare-filename}})
        posix-world (posix/world mem (net/target-descriptor)
                                 {:progress-limit 64
                                  :stream-capacity 8
                                  :pipe-capacity 1})
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
        controlled
        (runtime/run-controlled
         {:ffi-handlers handlers
          :drain-timeout-ms 10000}
         (fn []
           (fixture/exercise-outbox-delivery-reopen hermetic-spec command)))
        result (:result controlled)
        app (:application result)
        delivery (:delivery app)
        sqlite-state (sqlite/state sqlite-world)
        closed (:closed-db-evidence sqlite-state)]
    (testing "the unchanged ordinary application has exact real/hermetic parity"
      (when-not *sim-only?*
        (is (= real-result result))))

    (testing "the reopened connection observes the committed pending row"
      (is (= expected-identities (:identities app)))
      (is (= {:value fixture/default-command
              :result expected-result
              :emitted [expected-row]}
             (:command app)))
      ;; The reload ran on the reopened connection and must equal connection 0's
      ;; committed state: the pending row survived the clean close/reopen.
      (is (= expected-state (:pending-state app)))
      (is (= :pending (get-in app [:pending-state :outbox 0 :status]))))

    (testing "the validated ack gates one durable delivery marking on the reopen"
      (is (= expected-marking (:marking app)))
      (is (= expected-delivered-state (:store-state app)))
      (is (= :delivered (get-in app [:store-state :outbox 0 :status])))
      (is (empty? (filter #(= :pending (:status %))
                          (get-in app [:store-state :outbox])))))

    (testing "the reopened connection delivers the row in one framed bencode attempt"
      (is (= [expected-message] (:requests delivery)))
      (is (= [expected-ack] (:replies delivery)))
      (is (pos? (:sent-bytes delivery)))
      (is (pos? (:received-bytes delivery)))
      (is (= {:connection [true false]} (:close-results delivery))))

    (testing "the receiver observes the same semantic delivery and acknowledges it"
      (is (= [expected-message] (get-in result [:receiver :requests])))
      (is (empty? (get-in result [:receiver :server-errors]))))

    (testing "the HTTP response reports the committed command result byte-exactly"
      (is (= 200 (get-in result [:http :status])))
      (is (= "application/x-bencode"
             (get-in result [:http :content-type])))
      (is (= expected-http-response (get-in result [:http :response])))
      (is (empty? (get-in result [:http :server-errors])))
      (is (= {:connection [true false]}
             (get-in result [:http :close-results]))))

    (testing "all 25 SQLite plans and all native effects stay inside the model"
      (is (= {:plan-index 25
              :plan-count 25
              :open-dbs 0
              :active-stmts 0}
             (sqlite/summary sqlite-world)))
      (is (every? #(= :handler (:route %)) (:effect-trace controlled))))

    (testing "the clean close/reopen leaves two closed records and a durable image"
      ;; Two sequential single-owner connections, closed in order 0 then 1, each
      ;; owning the same selected filename image.
      (is (= 2 (count closed)))
      (is (= [0 1] (mapv :close-index closed)))
      (is (= [0 1] (mapv :connection-id closed)))
      (is (= [bare-filename bare-filename] (mapv :image-key closed)))
      ;; Each close was clean: no active transaction or staging escaped.
      (is (every? #(nil? (:staging %)) closed))
      ;; Connection 0 published the committed pending image at its close;
      ;; connection 1 reloaded it, marked it delivered, and published the
      ;; delivered image at its close.
      (is (= {:type :text :value "pending"}
             (get-in (first closed) [:committed modeled-outbox-key "status"])))
      (is (= {:type :text :value "delivered"}
             (get-in (second closed) [:committed modeled-outbox-key "status"])))
      ;; Marking changes exactly one modeled field. This catches a vacuous
      ;; reopen whose second connection is seeded from plans or loses another
      ;; table while still returning the expected projected application rows.
      (is (= (:committed (first closed))
             (assoc-in (:committed (second closed))
                       [modeled-outbox-key "status"]
                       {:type :text :value "pending"})))
      ;; The final published image is the delivered state and no selected
      ;; filename remains open.
      (is (= (:committed (second closed))
             (get-in sqlite-state [:images bare-filename])))
      (is (= #{} (:open-images sqlite-state))))

    (testing "the shared native worlds quiesce cleanly"
      (is (true? (memory/clean? mem)))
      (is (true? (sqlite/clean? sqlite-world)))
      (is (true? (posix/clean? posix-world))))))
