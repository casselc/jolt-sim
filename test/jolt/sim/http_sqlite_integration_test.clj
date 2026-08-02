(ns jolt.sim.http-sqlite-integration-test
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [jolt.net :as net]
            [jolt.sim.evidence.http-sqlite :as evidence]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.http-sqlite :as fixture]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]
            [jolt.sim.trace :as trace]))

(def ^:dynamic *sim-only?* false)

(def ^:private expected-blob-octets [0 65 127 128 255])

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
      (is (= (vec (:body real-parsed)) (vec (:body parsed)))))
    (is (= 200 (:status parsed)))
    (is (= "application/octet-stream"
           (get (:headers parsed) "content-type")))
    (is (= (str (count expected-blob-octets))
           (get (:headers parsed) "content-length")))
    (is (= expected-blob-octets (vec (:body parsed))))

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
    (is (true? (posix/clean? posix-world)))

    ;; The evidence-v1 document assembled from this same completed run
    ;; validates, is byte-stable/replayable through both a structural
    ;; restore-value round trip and a canonical-EDN print/read round trip, and
    ;; both post-hoc monitors pass over it.
    (let [build-args {:controlled controlled
                       :sqlite-world sqlite-world
                       :posix-world posix-world
                       :fault-frontend fault-frontend
                       :statement-plans (statement-plans)}
          doc (evidence/build-evidence build-args)]
      (is (= evidence/schema
             (:jolt.sim.evidence.http-sqlite/schema doc)))
      (is (= doc (evidence/validate-document! doc)))
      (is (= doc (trace/restore-value (trace/canonical-value doc))))
      (is (= doc (edn/read-string (evidence/canonical-edn doc))))
      (is (= :pass (:status (evidence/check-handler-only-cleanup-safety doc))))
      (is (= :pass
             (:status (evidence/check-bounded-request-completes-after-retry
                       doc))))

      ;; Rebuilding the document from the same already-completed run state is
      ;; deterministic: build-evidence performs no further FFI/scheduling/I-O
      ;; of its own, only reads live world/frontend snapshots, so a second
      ;; build from the same inputs is byte-identical to the first.
      (is (= doc (evidence/build-evidence build-args))))))
