(ns jolt.sim.http-sqlite-integration-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.http-sqlite :as fixture]
            [jolt.sim.fixtures.http-sqlite-scenarios :as scenarios]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

(def ^:private expected-blob-values [0 65 127 -128 -1])

(def ^:private interrupt-first-poll-plan
  [{:id :http-sqlite/interrupt-first-poll
    :match {:boundary :posix :operation :poll}
    :activation {:on-match 1 :times 1}
    :outcome {:kind :captured-error :errno :eintr}}])

;; Opening the connection runs "PRAGMA foreign_keys=1;" via the db.sqlite
;; connection initialization, so the four plans are exactly: PRAGMA, create,
;; insert, select.
(defn- statement-plans []
  [{:sql "PRAGMA foreign_keys=1;"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "create table sim_blob (id integer primary key, payload blob)"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "insert into sim_blob (id, payload) values (?, ?)"
    :params {1 {:type :integer :value 1}
             2 {:type :blob
                :value (byte-array [0 65 127 128 255])}}
    :columns []
    :rows []
    :changes 1
    :last-row-id 1}
   {:sql "select payload from sim_blob where id = ?"
    :params {1 {:type :integer :value 1}}
    :columns ["payload"]
    :rows [[{:type :blob
             :value (byte-array [0 65 127 128 255])}]]
    :changes 0
    :last-row-id 1}])

(def ^:private expected-sqlite-foreign-symbols
  #{"sqlite3_open"
    "sqlite3_close_v2"
    "sqlite3_prepare_v2"
    "sqlite3_step"
    "sqlite3_finalize"
    "sqlite3_column_count"
    "sqlite3_column_name"
    "sqlite3_column_type"
    "sqlite3_bind_int64"
    "sqlite3_bind_blob64"
    "sqlite3_column_blob"
    "sqlite3_column_bytes"
    "sqlite3_changes"})

(def ^:private expected-posix-foreign-symbols
  #{"accept" "bind" "close" "connect" "fcntl" "freeaddrinfo"
    "getaddrinfo" "getpeername" "getsockname" "listen" "pipe"
    "poll" "read" "recv" "send" "getsockopt" "setsockopt"
    "socket" "write"})

(defn- foreign-symbols [effects]
  (set
   (keep (fn [effect]
           (when (= :foreign-function (:kind effect))
             (:symbol effect)))
         effects)))

(deftest unchanged-jolt-http-and-db-code-runs-in-the-hermetic-world
  (let [real-result (when-not *sim-only?*
                      (fixture/exercise-http-sqlite))
        mem (memory/world)
        sqlite-world (sqlite/world mem (statement-plans))
        ;; A one-byte per-socket receive FIFO forces every HTTP octet through
        ;; the capacity model: the writer poll/recv handshake is exercised
        ;; end to end while ordinary library behavior and cleanup are retained.
        posix-world (posix/world mem (net/target-descriptor)
                                 {:stream-capacity 1
                                  :pipe-capacity 1})
        fault-frontend
        (posix-fault/frontend posix-world interrupt-first-poll-plan)
        ;; Three named packs: the shared memory native-operation handlers
        ;; registered exactly once, plus the SQLite and POSIX foreign packs
        ;; that contribute only their foreign-function keys over that same
        ;; memory world. No plain merge across complete packs.
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix
                  (posix-fault/foreign-handlers fault-frontend)))
        controlled
        (runtime/run-controlled
         {:ffi-handlers handlers
          :drain-timeout-ms 10000}
         fixture/exercise-http-sqlite)
        result (:result controlled)
        parsed (:parsed result)
        real-parsed (when real-result (:parsed real-result))]
    ;; Real/sim result parity: status, content type, content length, and the
    ;; exact BLOB octets (which span the signed/unsigned byte boundary).
    (when-not *sim-only?*
      (is (= (:status real-parsed) (:status parsed)))
      (is (= (get (:headers real-parsed) "content-type")
             (get (:headers parsed) "content-type")))
      (is (= (get (:headers real-parsed) "content-length")
             (get (:headers parsed) "content-length")))
      (is (= (vec (:body real-parsed)) (vec (:body parsed))))
      (is (empty? (:server-errors real-result))))
    (is (= 200 (:status parsed)))
    (is (= "application/octet-stream"
           (get (:headers parsed) "content-type")))
    (is (= (str (count expected-blob-values))
           (get (:headers parsed) "content-length")))
    (is (= expected-blob-values (vec (:body parsed))))
    (is (empty? (:server-errors result)))

    ;; Every intercepted POSIX and SQLite effect was served by a registered
    ;; handler; nothing routed to a real socket or the real SQLite library.
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))
    (is (= (set/union expected-sqlite-foreign-symbols
                      expected-posix-foreign-symbols)
           (foreign-symbols (:effects controlled))))

    ;; One deterministic captured EINTR crossed the ordinary public HTTP/net
    ;; stack through the existing poll handler. The application still produced
    ;; the exact DB-backed response, and every later poll delegated normally.
    (let [fault-snapshot (posix-fault/snapshot fault-frontend)
          history (posix-fault/evidence-history fault-frontend)]
      (is (pos? (posix-fault/attempts fault-frontend)))
      (is (= 1 (:firings fault-snapshot)))
      (is (= 1 (count (keep :firing history))))
      (is (= :http-sqlite/interrupt-first-poll
             (get-in history [0 :firing :rule-id])))
      (is (= {:kind :captured-error :errno :eintr}
             (get-in history [0 :firing :outcome]))))

    ;; SQLite: all four plans consumed; no live connections or statements.
    (is (= {:plan-index 4
            :plan-count 4
            :open-dbs 0
            :active-stmts 0}
           (sqlite/summary sqlite-world)))

    ;; POSIX: every modeled socket, pipe, listener, and addrinfo allocation
    ;; retired and no readiness waiter parked.
    (is (empty? (posix/snapshot posix-world)))
    (is (empty? (posix/pipe-snapshot posix-world)))
    (is (zero? (:waiter-count (posix/readiness-snapshot posix-world))))
    (is (empty? (get (posix/state posix-world) :listeners)))
    (is (empty? (get (posix/state posix-world) :addrinfo-allocations)))

    ;; The one-byte stream capacity was honored end to end: the configured
    ;; capacity is exactly 1, the back-pressure path produced both a partial
    ;; capacity-limited write and a would-block/retry, and a connected socket's
    ;; receive FIFO reached but never exceeded one byte.
    (let [summary (posix/capacity-summary posix-world)]
      (is (= 1 (:stream-capacity summary)))
      (is (pos? (:stream-capacity-limited-writes summary)))
      (is (pos? (:stream-would-blocks summary)))
      (is (= 1 (:max-stream-recv-bytes summary))))

    ;; The same ordinary run remains correct with a one-byte poller self-pipe.
    ;; The focused ordinary-poller fixture owns the deterministic EAGAIN claim;
    ;; an active HTTP reactor may drain between close's two wake attempts.
    (let [summary (posix/pipe-capacity-summary posix-world)]
      (is (= 1 (:pipe-capacity summary)))
      (is (= 1 (:max-pipe-fifo-bytes summary))))

    ;; The single shared memory world backs SQLite and POSIX, and is clean.
    (is (true? (memory/clean? mem)))
    (is (true? (sqlite/clean? sqlite-world)))
    (is (true? (posix/clean? posix-world)))))

;; ---- Ordinary jdbc/atomic transaction scenarios ---------------------------

;; Canonical application evidence per transaction scenario. :commit-visible
;; observes the committed BLOB row; :rollback-invisible observes no row after
;; the fixture's sentinel rolls the transaction back.
(def ^:private expected-transaction-http
  {:commit-visible
   {:status 200
    :content-type "application/octet-stream"
    :content-length "5"
    :server-errors []
    :body-octets [0 65 127 -128 -1]}
   :rollback-invisible
   {:status 404
    :content-type "application/octet-stream"
    :content-length "0"
    :server-errors []
    :body-octets []}})

;; Exact SQLite foreign symbols per scenario. Both lanes add
;; sqlite3_get_autocommit (the pinned jdbc depth-0 verified-begin probe) and
;; drop the BLOB scenario's unused keys; :rollback-invisible's get misses, so
;; no column accessor fires there at all.
(def ^:private expected-transaction-sqlite-foreign-symbols
  {:commit-visible
   #{"sqlite3_open"
     "sqlite3_close_v2"
     "sqlite3_prepare_v2"
     "sqlite3_step"
     "sqlite3_finalize"
     "sqlite3_column_count"
     "sqlite3_column_name"
     "sqlite3_column_type"
     "sqlite3_bind_int64"
     "sqlite3_bind_blob64"
     "sqlite3_column_blob"
     "sqlite3_column_bytes"
     "sqlite3_get_autocommit"
     "sqlite3_changes"}
   :rollback-invisible
   #{"sqlite3_open"
     "sqlite3_close_v2"
     "sqlite3_prepare_v2"
     "sqlite3_step"
     "sqlite3_finalize"
     "sqlite3_column_count"
     "sqlite3_bind_int64"
     "sqlite3_bind_blob64"
     "sqlite3_get_autocommit"
     "sqlite3_changes"}})

;; Physical boundary evidence the model must record for plan indices 2 (BEGIN)
;; and 4 (COMMIT/ROLLBACK): each directive applies exactly once, at the
;; statement's terminal step, against the physical autocommit state.
(def ^:private expected-transaction-tx-evidence
  {:commit-visible
   [{:sequence 0 :plan-index 2 :op :begin :when :on-success :reported :done
     :applied? true :reason nil :before-autocommit? true
     :after-autocommit? false}
    {:sequence 1 :plan-index 4 :op :commit :when :on-success :reported :done
     :applied? true :reason nil :before-autocommit? false
     :after-autocommit? true}]
   :rollback-invisible
   [{:sequence 0 :plan-index 2 :op :begin :when :on-success :reported :done
     :applied? true :reason nil :before-autocommit? true
     :after-autocommit? false}
    {:sequence 1 :plan-index 4 :op :rollback :when :on-success :reported :done
     :applied? true :reason nil :before-autocommit? false
     :after-autocommit? true}]})

;; Store visibility evidence: the insert (plan index 3) stages while the
;; transaction is open; the select (plan index 5) sees the staged value merged
;; into committed storage after COMMIT, and nothing after ROLLBACK.
(def ^:private expected-transaction-store-evidence
  {:commit-visible
   [{:sequence 0 :plan-index 3 :op :put :reported :done
     :key {:type :integer :value 1} :location :staging :present? true}
    {:sequence 1 :plan-index 5 :op :get :reported :done
     :key {:type :integer :value 1} :location :committed :present? true}]
   :rollback-invisible
   [{:sequence 0 :plan-index 3 :op :put :reported :done
     :key {:type :integer :value 1} :location :staging :present? true}
    {:sequence 1 :plan-index 5 :op :get :reported :done
     :key {:type :integer :value 1} :location nil :present? false}]})

(def ^:private expected-transaction-committed
  {:commit-visible {{:type :integer :value 1}
                    {:type :blob :value [0 65 127 128 255]}}
   :rollback-invisible {}})

(deftest unchanged-transaction-code-runs-in-real-and-hermetic-worlds
  (doseq [scenario [:commit-visible :rollback-invisible]]
    (testing (name scenario)
      (let [real (when-not *sim-only?*
                   (scenarios/run-transaction-scenario scenario :real))
            simulated (scenarios/run-transaction-scenario scenario :simulated)]
        ;; Real/sim canonical application evidence parity: one exact equality
        ;; over the whole projection. Host-only incidental fields (ephemeral
        ;; port, connection-info addresses, sent/close results, the Date
        ;; header value, the reason phrase) are excluded by name inside the
        ;; projection, not by weakening this equality.
        (when-not *sim-only?*
          (is (= (:http real) (:http simulated))))
        ;; :raw-length is exact cross-lane framing evidence (compared above),
        ;; not a scenario-owned literal -- header layout belongs to jolt-http,
        ;; so it is excluded by name from the literal check only.
        (is (= (get expected-transaction-http scenario)
               (dissoc (:http simulated) :raw-length)))

        ;; Every intercepted POSIX and SQLite effect was served by a
        ;; registered handler; nothing routed to a real socket or the real
        ;; SQLite library.
        (is (true? (get-in simulated [:routes :all-handled?])))
        (is (= (set/union (get expected-transaction-sqlite-foreign-symbols
                               scenario)
                          expected-posix-foreign-symbols)
               (set (get-in simulated [:routes :foreign-symbols]))))

        ;; SQLite: all six plans consumed; no live connections or statements.
        (is (= {:plan-index 6
                :plan-count 6
                :open-dbs 0
                :active-stmts 0}
               (get-in simulated [:sqlite :summary])))

        ;; The closed connection's physical boundary evidence proves the
        ;; transaction semantics behind the application-visible result: one
        ;; verified-begin autocommit probe, the exact BEGIN + COMMIT/ROLLBACK
        ;; transitions, the staged put, and the get's post-boundary
        ;; visibility.
        (let [closed (get-in simulated [:sqlite :closed-db-evidence])]
          (is (= 1 (count closed)))
          (let [db (first closed)]
            (is (true? (:autocommit? db)))
            (is (nil? (:tx db)))
            (is (= [1] (:autocommit-evidence db)))
            (is (= (get expected-transaction-tx-evidence scenario)
                   (:tx-evidence db)))
            (is (= (get expected-transaction-store-evidence scenario)
                   (:store-evidence db)))
            (is (= (get expected-transaction-committed scenario)
                   (:committed db)))
            (is (nil? (:staging db)))))

        ;; The single shared memory world backs SQLite and POSIX, and every
        ;; world is clean.
        (is (= {:memory true :sqlite true :posix true}
               (:clean? simulated)))))))

;; ---- Begin-recovery / poisoning scenarios ----------------------------------

(def ^:private expected-recovery-empty-http
  {:status 200
   :content-type "application/octet-stream"
   :content-length "0"
   :server-errors []
   :body-octets []})

(deftest uncertain-begin-recovered-runs-in-the-hermetic-world
  (let [result (scenarios/run-recovery-scenario :uncertain-begin-recovered :simulated)]
    (testing "http"
      (is (= {:status 200
              :content-type "application/octet-stream"
              :content-length "5"
              :server-errors []
              :body-octets [0 65 127 -128 -1]}
             (dissoc (:http result) :raw-length))))
    (testing "observations"
      (is (= {:primary {:caught? true :rc 5
                        :message "sqlite step failed: database is locked"}
              :body-ran-before-recovery? false
              :retried? true
              :retry-body-ran? true}
             (:observations result))))
    (testing "routes"
      (is (true? (get-in result [:routes :all-handled?])))
      (is (= (set/union expected-posix-foreign-symbols
                        #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_errmsg"
                          "sqlite3_prepare_v2" "sqlite3_step" "sqlite3_finalize"
                          "sqlite3_column_count" "sqlite3_column_name"
                          "sqlite3_column_type" "sqlite3_bind_int64"
                          "sqlite3_bind_blob64" "sqlite3_column_blob"
                          "sqlite3_column_bytes" "sqlite3_get_autocommit"
                          "sqlite3_changes"})
             (set (get-in result [:routes :foreign-symbols])))))
    (testing "sqlite plan consumption and physical evidence"
      (is (= {:plan-index 8 :plan-count 8 :open-dbs 0 :active-stmts 0}
             (get-in result [:sqlite :summary])))
      (let [closed (get-in result [:sqlite :closed-db-evidence])]
        (is (= 1 (count closed)))
        (let [db (first closed)]
          (is (true? (:autocommit? db)))
          (is (nil? (:tx db)))
          (is (= [1 0 1 1] (:autocommit-evidence db)))
          (is (= [{:sequence 0 :plan-index 2 :op :begin :when :always
                   :reported :error :applied? true :reason nil
                   :before-autocommit? true :after-autocommit? false}
                  {:sequence 1 :plan-index 3 :op :rollback :when :on-success
                   :reported :done :applied? true :reason nil
                   :before-autocommit? false :after-autocommit? true}
                  {:sequence 2 :plan-index 4 :op :begin :when :on-success
                   :reported :done :applied? true :reason nil
                   :before-autocommit? true :after-autocommit? false}
                  {:sequence 3 :plan-index 6 :op :commit :when :on-success
                   :reported :done :applied? true :reason nil
                   :before-autocommit? false :after-autocommit? true}]
                 (:tx-evidence db)))
          (is (= [{:sequence 0 :plan-index 5 :op :put :reported :done
                   :key {:type :integer :value 1} :location :staging
                   :present? true}
                  {:sequence 1 :plan-index 7 :op :get :reported :done
                   :key {:type :integer :value 1} :location :committed
                   :present? true}]
                 (:store-evidence db)))
          (is (= {{:type :integer :value 1}
                  {:type :blob :value [0 65 127 128 255]}}
                 (:committed db)))
          (is (nil? (:staging db))))))
    (testing "clean worlds"
      (is (= {:memory true :sqlite true :posix true} (:clean? result))))))

(deftest counter-rollback-unverified-poisoned-runs-in-the-hermetic-world
  (let [result (scenarios/run-recovery-scenario
                :counter-rollback-unverified-poisoned :simulated)]
    (is (= expected-recovery-empty-http (dissoc (:http result) :raw-length)))
    (is (= {:primary {:caught? true :rc 5
                      :message "sqlite step failed: database is locked"}
            :follow-up {:rejected? true
                        :message "connection transaction state is indeterminate; close connection"
                        :poisoned? true
                        :close-required? true
                        :cleanup [{:phase :begin-rollback-verify
                                   :sql "sqlite3_get_autocommit"}]}}
           (:observations result)))
    (is (true? (get-in result [:routes :all-handled?])))
    (is (= (set/union expected-posix-foreign-symbols
                      #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_errmsg"
                        "sqlite3_prepare_v2" "sqlite3_step" "sqlite3_finalize"
                        "sqlite3_column_count"
                        "sqlite3_get_autocommit" "sqlite3_changes"})
           (set (get-in result [:routes :foreign-symbols]))))
    (is (= {:plan-index 4 :plan-count 4 :open-dbs 0 :active-stmts 0}
           (get-in result [:sqlite :summary])))
    (let [db (first (get-in result [:sqlite :closed-db-evidence]))]
      (is (false? (:autocommit? db)))
      (is (= [1 0 0] (:autocommit-evidence db)))
      (is (= [{:sequence 0 :plan-index 2 :op :begin :when :always
               :reported :error :applied? true :reason nil
               :before-autocommit? true :after-autocommit? false}
              {:sequence 1 :plan-index 3 :op :rollback :when :never
               :reported :done :applied? false :reason :withheld
               :before-autocommit? false :after-autocommit? false}]
             (:tx-evidence db)))
      (is (empty? (:store-evidence db)))
      (is (some? (:discarded-transaction db)))
      (is (= {} (:discarded-staging db)))
      (is (= {} (:committed db))))
    (is (= {:memory true :sqlite true :posix true} (:clean? result)))))

(deftest counter-rollback-failed-poisoned-runs-in-the-hermetic-world
  (let [result (scenarios/run-recovery-scenario
                :counter-rollback-failed-poisoned :simulated)]
    (is (= expected-recovery-empty-http (dissoc (:http result) :raw-length)))
    (is (= {:primary {:caught? true :rc 5
                      :message "sqlite step failed: database is locked"}
            :follow-up {:rejected? true
                        :message "connection transaction state is indeterminate; close connection"
                        :poisoned? true
                        :close-required? true
                        :cleanup [{:phase :begin-rollback :sql "ROLLBACK"}]}}
           (:observations result)))
    (is (true? (get-in result [:routes :all-handled?])))
    (is (= (set/union expected-posix-foreign-symbols
                      #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_errmsg"
                        "sqlite3_prepare_v2" "sqlite3_step" "sqlite3_finalize"
                        "sqlite3_column_count"
                        "sqlite3_get_autocommit" "sqlite3_changes"})
           (set (get-in result [:routes :foreign-symbols]))))
    (is (= {:plan-index 4 :plan-count 4 :open-dbs 0 :active-stmts 0}
           (get-in result [:sqlite :summary])))
    (let [db (first (get-in result [:sqlite :closed-db-evidence]))]
      (is (false? (:autocommit? db)))
      (is (= [1 0] (:autocommit-evidence db)))
      (is (= [{:sequence 0 :plan-index 2 :op :begin :when :always
               :reported :error :applied? true :reason nil
               :before-autocommit? true :after-autocommit? false}
              {:sequence 1 :plan-index 3 :op :rollback :when :on-success
               :reported :error :applied? false :reason :reported-error
               :before-autocommit? false :after-autocommit? false}]
             (:tx-evidence db)))
      (is (empty? (:store-evidence db)))
      (is (some? (:discarded-transaction db)))
      (is (= {} (:discarded-staging db)))
      (is (= {} (:committed db))))
    (is (= {:memory true :sqlite true :posix true} (:clean? result)))))

(def ^:private expected-preexisting-observations
  {:primary {:caught? true
             :message "connection is physically inside a transaction while logical depth is zero; close connection"
             :poisoned? true
             :close-required? true}
   :follow-up {:rejected? true
               :message "connection transaction state is indeterminate; close connection"
               :poisoned? true
               :close-required? true
               :cleanup [{:phase :begin-precondition :sql "BEGIN"}]}})

(deftest preexisting-transaction-poisoned-runs-in-real-and-hermetic-worlds
  (let [real (when-not *sim-only?*
               (scenarios/run-recovery-scenario
                :preexisting-transaction-poisoned :real))
        simulated (scenarios/run-recovery-scenario
                   :preexisting-transaction-poisoned :simulated)]
    (when-not *sim-only?*
      (is (= (:http real) (:http simulated)))
      (is (= expected-preexisting-observations (:observations real))))
    (is (= expected-recovery-empty-http
           (dissoc (:http simulated) :raw-length)))
    (is (= expected-preexisting-observations (:observations simulated)))
    (is (true? (get-in simulated [:routes :all-handled?])))
    (is (= (set/union expected-posix-foreign-symbols
                      #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_prepare_v2"
                        "sqlite3_step" "sqlite3_finalize" "sqlite3_column_count"
                        "sqlite3_bind_int64"
                        "sqlite3_bind_blob64" "sqlite3_get_autocommit"
                        "sqlite3_changes"})
           (set (get-in simulated [:routes :foreign-symbols]))))
    (is (= {:plan-index 4 :plan-count 4 :open-dbs 0 :active-stmts 0}
           (get-in simulated [:sqlite :summary])))
    (let [db (first (get-in simulated [:sqlite :closed-db-evidence]))]
      (is (false? (:autocommit? db)))
      (is (= [0] (:autocommit-evidence db)))
      (is (= [{:sequence 0 :plan-index 2 :op :begin :when :on-success
               :reported :done :applied? true :reason nil
               :before-autocommit? true :after-autocommit? false}]
             (:tx-evidence db)))
      (is (= [{:sequence 0 :plan-index 3 :op :put :reported :done
               :key {:type :integer :value 1} :location :staging
               :present? true}]
             (:store-evidence db)))
      (is (some? (:discarded-transaction db)))
      (is (= {{:type :integer :value 1}
              {:type :blob :value [0 65 127 128 255]}}
             (:discarded-staging db)))
      (is (= {} (:committed db))))
    (is (= {:memory true :sqlite true :posix true} (:clean? simulated)))))
