(ns jolt.sim.fixtures.http-sqlite
  "Ordinary jolt-http + db.sqlite application code used unchanged against real
  sockets plus real SQLite and the hermetic POSIX loopback + SQLite handler
  pack.

  This namespace deliberately knows nothing about jolt-sim. It depends only on
  public jdbc.core, jolt.http.server, teensyp.client, and basic Clojure. Its
  caller decides whether the underlying POSIX and SQLite foreign calls reach
  the host or a simulated FFI world.

  The request handler opens an in-memory SQLite database. Opening that
  connection runs \"PRAGMA foreign_keys=1;\" via the db.sqlite connection
  initialization before any explicit statement is prepared. The handler then
  creates one table, inserts a BLOB whose octets span the signed/unsigned byte
  boundary, reads that BLOB back, and returns the fetched byte array verbatim
  as an application/octet-stream HTTP body. The response framing is parsed by
  scanning raw bytes for the header terminator, so the binary body survives
  byte-exact while only the headers are decoded.

  A second ordinary handler exercises unchanged public jdbc/atomic
  transaction code over the same table: it inserts the fixture BLOB inside
  one atomic transaction, then -- after atomic returns -- queries through
  ordinary JDBC and reports what the transaction boundary made visible. One
  scenario commits and observes the row; the other throws a private fixture
  sentinel from the atomic body so jdbc rolls back, catches only that
  sentinel, and observes no row."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [teensyp.client :as client]))

(def ^:private blob-octets [0 65 127 128 255])

(defn- blob-handler [_request]
  (with-open [connection (jdbc/connection "sqlite::memory:")]
    ;; Opening the connection runs "PRAGMA foreign_keys=1;" via the db.sqlite
    ;; connection initialization before the first explicit statement below.
    (jdbc/execute!
     connection
     "create table sim_blob (id integer primary key, payload blob)")
    (jdbc/execute!
     connection
     ["insert into sim_blob (id, payload) values (?, ?)"
      1
      (byte-array blob-octets)])
    (let [row (jdbc/fetch-one
               connection
               ["select payload from sim_blob where id = ?" 1])]
      {:status  200
       :headers {"Content-Type" "application/octet-stream"}
       :body    (:payload row)})))

(defn- request-bytes [host port]
  (.getBytes
   (str "GET / HTTP/1.1\r\n"
        "Host: " host ":" port "\r\n"
        "Connection: close\r\n"
        "\r\n")
   "UTF-8"))

(defn- copy-of-length ^bytes [^bytes src n]
  (let [dest (byte-array n)]
    (System/arraycopy src 0 dest 0 n)
    dest))

(defn- copy-range ^bytes [^bytes src start end]
  (let [n (- end start)
        dest (byte-array n)]
    (System/arraycopy src start dest 0 n)
    dest))

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        dest (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (do
          (System/arraycopy chunk 0 dest offset (alength chunk))
          (recur (rest remaining) (+ offset (alength chunk))))
        dest))))

(defn- read-response-until-eof!
  "Reads every byte the server sends until it closes, via only the public
  teensyp.client blocking API."
  [connection]
  (let [scratch (byte-array 4096)]
    (loop [chunks []]
      (let [n (client/receive-into! connection scratch 0 (alength scratch)
                                     {:timeout-ms 5000})]
        (if (nil? n)
          (concat-byte-arrays chunks)
          (recur (conj chunks (copy-of-length scratch n))))))))

(defn- ubyte [^bytes arr i]
  (bit-and 0xFF (int (aget arr i))))

(defn- find-header-terminator
  "Returns the byte index of the first \\r\\n\\r\\n sequence in raw, or nil.
  Scanning raw bytes keeps the search independent of any body decoding."
  [^bytes raw]
  (let [n (alength raw)]
    (loop [i 0]
      (if (> (+ i 4) n)
        nil
        (if (and (= 13 (ubyte raw i))
                 (= 10 (ubyte raw (+ i 1)))
                 (= 13 (ubyte raw (+ i 2)))
                 (= 10 (ubyte raw (+ i 3))))
          i
          (recur (inc i)))))))

(defn- stable-error-summary
  "Projects a server error into the simulator's stable trace domain without
  retaining host exception objects in scenario evidence."
  [error]
  {:class (str (class error))
   :message (or (ex-message error)
                (try (jolt.host/condition-message error)
                     (catch :default _ nil)))
   :data (when-let [data (ex-data error)]
           (into {}
                 (map (fn [[k v]] [(str k) (pr-str v)]))
                 data))})

(defn- parse-response
  "Parses one HTTP/1.1 response into {:status :reason :headers :body}. Only the
  fixed response framing is parsed: the headers are decoded as UTF-8 while the
  body is preserved as the exact raw byte array -- this is a minimal reader for
  the fixed, unchunked reply this fixture itself requested with
  `Connection: close`, not a general HTTP parser."
  [^bytes raw server-errors]
  (let [terminator (find-header-terminator raw)]
    (when-not terminator
      (throw (ex-info "http-sqlite fixture response missing header terminator"
                      {:raw-length (alength raw)
                       ;; Preserve a stable, byte-exact failure witness. This is
                       ;; deliberately a vector rather than the host byte-array
                       ;; so worker transport and Hegel replay can encode it.
                       :raw-bytes (vec raw)
                       :server-errors @server-errors})))
    (let [head-bytes (copy-range raw 0 terminator)
          head (String. head-bytes "UTF-8")
          body (copy-range raw (+ terminator 4) (alength raw))
          [status-line & header-lines] (str/split head #"\r\n")
          [_ status reason] (re-matches #"HTTP/1\.1 (\d{3}) (.*)" status-line)
          headers (into {}
                        (map (fn [line]
                               (let [colon (str/index-of line ":")]
                                 [(str/lower-case (subs line 0 colon))
                                  (str/trim (subs line (inc colon)))])))
                        header-lines)]
      {:status  (Long/parseLong status)
       :reason  reason
       :headers headers
       :body    body})))

(defn- run-request-cycle
  "Starts a jolt-http server on an ephemeral port with `handler`, drives one
   request/response cycle through the public teensyp.client -- itself built
   exclusively on jolt.net -- and returns the raw result map shared by every
   scenario in this namespace. Port 0 selects an ephemeral listener; the
   server's actual bound port is read back from its own public return value."
  [handler]
  (let [server-errors (atom [])
        server (http/run-server handler
                                 :port 0
                                 :reuse-address? true
                                 :error-logger
                                 #(swap! server-errors conj
                                         (stable-error-summary %)))
        connection* (atom nil)]
    (let [result
          (try
            (let [port (:port server)
                  connection (client/connect "127.0.0.1" port
                                              {:connect-timeout-ms 5000})
                  _ (reset! connection* connection)
                  request (request-bytes "127.0.0.1" port)
                  sent (client/send-all! connection request {:timeout-ms 5000})
                  raw (read-response-until-eof! connection)
                  parsed (parse-response raw server-errors)]
              {:port port
               :sent-result sent
               :raw-length (alength raw)
               :parsed parsed
               :connection-info (client/connection-info connection)
               :close-results
               {:connection [(client/close! connection)
                             (client/close! connection)]}})
            (finally
              (when-let [connection @connection*]
                (client/close! connection))
              (http/stop-server server)))]
      ;; stop-server quiesces the reactor and active handlers, either of which
      ;; may report a late error. Snapshot only after shutdown has completed.
      (assoc result :server-errors @server-errors))))

(defn exercise-http-sqlite
  "Runs a jolt-http server whose synchronous handler opens an in-memory SQLite
   database, inserts and reads back a BLOB spanning the signed/unsigned byte
   boundary, and serves the fetched octets as an application/octet-stream body."
  []
  (run-request-cycle blob-handler))

;; Private sentinel identity marking only this fixture's deliberate rollback
;; request. The transaction handler catches the atomic body's exception only
;; when its ex-data carries exactly this value; every other error propagates
;; unchanged, so arbitrary failures are never swallowed.
(def ^:private rollback-sentinel (Object.))

(defn- transaction-handler
  "Builds the ordinary transaction handler for `scenario`. The handler opens
   an in-memory database, creates the fixture table, inserts the fixture BLOB
   inside one successful jdbc/atomic transaction and -- only after atomic
   returns -- queries the row back through ordinary JDBC. :commit-visible
   lets the transaction commit and observes the committed row;
   :rollback-invisible throws the private fixture sentinel from the atomic
   body so jdbc rolls the transaction back, catches only that sentinel, and
   observes no row."
  [scenario]
  (fn transaction-request-handler [_request]
    (with-open [connection (jdbc/connection "sqlite::memory:")]
      ;; Opening the connection runs "PRAGMA foreign_keys=1;" via the
      ;; db.sqlite connection initialization before the first explicit
      ;; statement below.
      (jdbc/execute!
       connection
       "create table sim_blob (id integer primary key, payload blob)")
      (try
        (jdbc/atomic connection
          (jdbc/execute!
           connection
           ["insert into sim_blob (id, payload) values (?, ?)"
            1
            (byte-array blob-octets)])
          (case scenario
            :commit-visible nil
            :rollback-invisible
            (throw (ex-info "http-sqlite fixture requested rollback"
                            {:sentinel rollback-sentinel}))))
        (catch Exception error
          (when-not (identical? rollback-sentinel
                                (:sentinel (ex-data error)))
            (throw error))))
      (let [row (jdbc/fetch-one
                 connection
                 ["select payload from sim_blob where id = ?" 1])]
        (if-let [payload (:payload row)]
          {:status  200
           :headers {"Content-Type" "application/octet-stream"}
           :body    payload}
          {:status  404
           :headers {"Content-Type" "application/octet-stream"}
           :body    (byte-array 0)})))))

(defn exercise-http-sqlite-transaction
  "Runs the same one-request lifecycle as exercise-http-sqlite with the
   ordinary transaction handler for `scenario` (:commit-visible or
   :rollback-invisible). The scenario keyword selects application behavior
   only; whether the underlying POSIX and SQLite foreign calls reach the host
   or a simulated FFI world remains the caller's choice, exactly as for the
   BLOB scenario."
  [scenario]
  (run-request-cycle (transaction-handler scenario)))

;; ---- Begin-recovery / poisoning scenarios ----------------------------------
;; The four scenarios below exercise the pinned db.jdbc verified-sqlite-begin!
;; recovery contract: an uncertain BEGIN failure that jdbc.core reconciles
;; against sqlite3_get_autocommit before deciding whether the connection is
;; reusable. Every predicate below matches the pinned jdbc.core/db.sqlite
;; exception shapes exactly (code, message, and/or ex-data flags) -- never an
;; arbitrary Throwable -- so a real logic bug elsewhere is never silently
;; absorbed as "the injected failure".

(def injected-begin-error
  "The exact simulated BEGIN failure this fixture's exact-predicate recovery
   and poisoning logic recognizes. It only ever surfaces when a simulated
   SQLite world's BEGIN plan declares precisely this :error; real SQLite
   never reports it, so every scenario that injects it is simulated-only."
  {:code 5 :msg "database is locked"})

(def injected-rollback-error
  "The exact simulated counter-rollback failure the
   :counter-rollback-failed-poisoned scenario's SQLite plan declares on its
   recovery ROLLBACK. The ordinary fixture never inspects this error
   directly -- jdbc.core's own verified-sqlite-begin! catches it internally
   and retains it only inside the poisoned connection's
   :jdbc/transaction-cleanup context, which the fixture observes secondhand
   through the later rejected JDBC operation."
  {:code 10 :msg "disk I/O error"})

(defn- injected-begin-error?
  "True only for the exact db.sqlite step-failure exception the injected
   BEGIN plan produces: the same :rc code and the same fully-formatted
   message db.sqlite's run-prepared builds from the plan's :error. Never
   matches on class alone."
  [error]
  (and (= (:code injected-begin-error) (:rc (ex-data error)))
       (= (str "sqlite step failed: " (:msg injected-begin-error))
          (ex-message error))))

(defn- preexisting-transaction-error?
  "True only for jdbc.core's own stable R6 divergence exception: a physical
   transaction observed active while logical depth is zero, at the exact
   message jdbc.core authors plus its exact :jdbc/transaction-poisoned and
   :jdbc/close-required data flags."
  [error]
  (let [data (ex-data error)]
    (and (true? (:jdbc/transaction-poisoned data))
         (true? (:jdbc/close-required data))
         (= "connection is physically inside a transaction while logical depth is zero; close connection"
            (ex-message error)))))

(defn- poisoned-transaction-error?
  "True only for jdbc.core's own stable poisoned-connection exception raised
   by ensure-transaction-usable! once a prior boundary failure has poisoned
   the connection, at jdbc.core's exact message and exact
   :jdbc/transaction-poisoned/:jdbc/close-required data flags. Distinguished
   from preexisting-transaction-error? by message text alone -- both share
   the same two data flags."
  [error]
  (let [data (ex-data error)]
    (and (true? (:jdbc/transaction-poisoned data))
         (true? (:jdbc/close-required data))
         (= "connection transaction state is indeterminate; close connection"
            (ex-message error)))))

(defn- insert-blob! [connection]
  (jdbc/execute!
   connection
   ["insert into sim_blob (id, payload) values (?, ?)"
    1
    (byte-array blob-octets)]))

(defn- select-blob-response [connection]
  (let [row (jdbc/fetch-one
             connection
             ["select payload from sim_blob where id = ?" 1])]
    (if-let [payload (:payload row)]
      {:status  200
       :headers {"Content-Type" "application/octet-stream"}
       :body    payload}
      {:status  404
       :headers {"Content-Type" "application/octet-stream"}
       :body    (byte-array 0)})))

(def ^:private empty-recovery-response
  {:status  200
   :headers {"Content-Type" "application/octet-stream"}
   :body    (byte-array 0)})

(defn- recovery-handler
  "Builds the ordinary begin-recovery/poisoning handler for `scenario`. Every
   branch opens an in-memory database and creates the fixture table exactly
   like the other handlers in this namespace, then drives one of the four
   application scenarios described on exercise-http-sqlite-recovery, recording
   the exact-predicate primary (and, where applicable, follow-up) outcome into
   `observations` -- a plain map atom owned by the caller. Each recorded
   outcome carries only stable, address-free data (predicate match, :rc/
   message text, and jdbc.core's own :jdbc/transaction-cleanup phase/sql
   context); no Throwable object or host identity is ever retained."
  [scenario observations]
  (fn recovery-request-handler [_request]
    (with-open [connection (jdbc/connection "sqlite::memory:")]
      ;; Opening the connection runs "PRAGMA foreign_keys=1;" via the
      ;; db.sqlite connection initialization before the first explicit
      ;; statement below.
      (jdbc/execute!
       connection
       "create table sim_blob (id integer primary key, payload blob)")
      (case scenario
        :uncertain-begin-recovered
        (let [body-ran? (atom false)
              primary
              (try
                (jdbc/atomic connection
                  (reset! body-ran? true)
                  (insert-blob! connection))
                nil
                (catch Exception error
                  (when-not (injected-begin-error? error)
                    (throw error))
                  {:caught?  true
                   :rc       (:rc (ex-data error))
                   :message  (ex-message error)}))]
          (swap! observations assoc
                 :primary primary
                 :body-ran-before-recovery? @body-ran?)
          (let [retry-body-ran? (atom false)]
            (jdbc/atomic connection
              (reset! retry-body-ran? true)
              (insert-blob! connection))
            (swap! observations assoc
                   :retried? true
                   :retry-body-ran? @retry-body-ran?))
          (select-blob-response connection))

        (:counter-rollback-unverified-poisoned
         :counter-rollback-failed-poisoned)
        (let [primary
              (try
                (jdbc/atomic connection (insert-blob! connection))
                nil
                (catch Exception error
                  (when-not (injected-begin-error? error)
                    (throw error))
                  {:caught?  true
                   :rc       (:rc (ex-data error))
                   :message  (ex-message error)}))
              follow-up
              (try
                (jdbc/fetch-one
                 connection
                 ["select payload from sim_blob where id = ?" 1])
                nil
                (catch Exception error
                  (when-not (poisoned-transaction-error? error)
                    (throw error))
                  {:rejected?        true
                   :message          (ex-message error)
                   :poisoned?        (true? (:jdbc/transaction-poisoned (ex-data error)))
                   :close-required?  (true? (:jdbc/close-required (ex-data error)))
                   :cleanup          (:jdbc/transaction-cleanup (ex-data error))}))]
          (swap! observations assoc :primary primary :follow-up follow-up)
          empty-recovery-response)

        :preexisting-transaction-poisoned
        (do
          ;; A raw public execute! -- outside jdbc/atomic entirely -- opens a
          ;; physical transaction at logical depth zero, then inserts the
          ;; fixture BLOB into it, before jdbc/atomic is ever invoked.
          (jdbc/execute! connection "BEGIN")
          (insert-blob! connection)
          (let [primary
                (try
                  (jdbc/atomic connection (insert-blob! connection))
                  nil
                  (catch Exception error
                    (when-not (preexisting-transaction-error? error)
                      (throw error))
                    {:caught?          true
                     :message          (ex-message error)
                     :poisoned?        (true? (:jdbc/transaction-poisoned (ex-data error)))
                     :close-required?  (true? (:jdbc/close-required (ex-data error)))}))
                follow-up
                (try
                  (jdbc/fetch-one
                   connection
                   ["select payload from sim_blob where id = ?" 1])
                  nil
                  (catch Exception error
                    (when-not (poisoned-transaction-error? error)
                      (throw error))
                    {:rejected?        true
                     :message          (ex-message error)
                     :poisoned?        (true? (:jdbc/transaction-poisoned (ex-data error)))
                     :close-required?  (true? (:jdbc/close-required (ex-data error)))
                     :cleanup          (:jdbc/transaction-cleanup (ex-data error))}))]
            (swap! observations assoc :primary primary :follow-up follow-up)
            empty-recovery-response))))))

(defn exercise-http-sqlite-recovery
  "Runs the same one-request lifecycle as exercise-http-sqlite-transaction
   with the ordinary begin-recovery/poisoning handler for `scenario`:

   - :uncertain-begin-recovered -- the first jdbc/atomic attempt's BEGIN
     physically begins but reports the injected begin error; the fixture
     catches only that exact error, proves its atomic body never ran, retries
     through a second successful jdbc/atomic that inserts the BLOB and
     commits, then selects and observes the committed row.
   - :counter-rollback-unverified-poisoned / :counter-rollback-failed-poisoned
     -- the same injected BEGIN failure, but jdbc.core's own counter-rollback
     recovery cannot prove the connection clean, so it poisons the connection
     and rethrows the original begin error as primary; the fixture catches
     only that exact error, then attempts one later ordinary JDBC query and
     observes it rejected by jdbc.core's own poisoned-connection guard.
   - :preexisting-transaction-poisoned -- a raw public execute! opens a
     physical transaction and inserts the BLOB at logical depth zero, before
     jdbc/atomic is invoked; jdbc.core's pre-BEGIN probe observes the
     divergence and fails closed without issuing an atomic-owned BEGIN or any
     ROLLBACK, and a later ordinary JDBC query is again observed rejected.

   Returns the same raw result map as exercise-http-sqlite-transaction, plus
   an :observations key: the handler's own plain, Throwable-free summary of
   its exact-predicate primary and follow-up outcomes. The scenario keyword
   selects application behavior only; whether the underlying POSIX and SQLite
   foreign calls reach the host or a simulated FFI world remains the caller's
   choice, exactly as for the other scenarios in this namespace."
  [scenario]
  (let [observations (atom {})]
    (assoc (run-request-cycle (recovery-handler scenario observations))
           :observations @observations)))

;; ---- Historical BEGIN fail-open control ------------------------------------
;; A permanent, test-only reproduction of the pre-fix jdbc.core BEGIN
;; fail-open decision. Unlike every other scenario above, this control calls
;; the public jdbc/execute! with "BEGIN" directly -- never jdbc/atomic -- and
;; evaluates its own deliberately buggy historical logical-depth decision
;; locally, right here. It never calls jdbc.core's own corrected
;; verified-sqlite-begin! recovery path, so the pinned DB implementation is
;; neither weakened nor exercised by this control.

(defn exercise-http-sqlite-begin-fail-open-control
  "Opens an in-memory SQLite connection -- whose initialization already runs
   \"PRAGMA foreign_keys=1;\" via the db.sqlite connection initialization --
   then calls the public jdbc/execute! with \"BEGIN\" directly. On success,
   the historical logical evaluator reports logical depth 1. On only the
   exact injected-begin-error? shape, the deliberately buggy historical
   evaluator catches it and reports logical depth 0 -- ready/reusable --
   without ever probing sqlite3_get_autocommit or issuing a recovery
   ROLLBACK. Every other exception propagates unchanged. Returns
   {:logical-depth 0-or-1}."
  []
  (with-open [connection (jdbc/connection "sqlite::memory:")]
    (let [logical-depth
          (try
            (jdbc/execute! connection "BEGIN")
            1
            (catch Exception error
              (if (injected-begin-error? error)
                0
                (throw error))))]
      {:logical-depth logical-depth})))
