(ns jolt.sim.http-sqlite-integration-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.http-sqlite :as fixture]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

(def ^:private expected-blob-octets [0 65 127 128 255])

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
        posix-world (posix/world mem (net/target-descriptor))
        ;; Three named packs: the shared memory native-operation handlers
        ;; registered exactly once, plus the SQLite and POSIX foreign packs
        ;; that contribute only their foreign-function keys over that same
        ;; memory world. No plain merge across complete packs.
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
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

    ;; The single shared memory world backs SQLite and POSIX, and is clean.
    (is (true? (memory/clean? mem)))
    (is (true? (sqlite/clean? sqlite-world)))
    (is (true? (posix/clean? posix-world)))))
