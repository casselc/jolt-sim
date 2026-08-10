(ns outbox-workbench.eval-flow-test-main
  "Focused fresh-process gate for the eval-only outbox workbench.

  Starts one eval-only Ripple server programmatically on port 0 over one real
  jolt.sim.eval-session/EvalSession, then drives the documented four browser
  forms -- require the unchanged fixture, def canonical-run through
  exercise-outbox-json-delivery, project [HTTP 201, durable :delivered, ack
  type outbox_delivery_ok], and recall *1 -- through the real loopback
  /api/eval HTTP endpoint with the capability token, once each, in order.

  Verified: monotonic sequence receipts 0..3, the exact projected vector both
  on the bounded wire and in the session's retained raw envelopes, *1 recall,
  empty HTTP and receiver server errors, and idempotent [true false]
  connection closes on both the HTTP command connection and the TCP delivery
  connection. Ripple is always stopped and the session always closed after
  the completed evaluations; the whole scenario runs under one watchdog and
  appends start/finish/timeout/error breadcrumbs to a retained progress file.
  The process ends with System/exit because core.async threads linger.

  This harness owns no application, HTTP, SQLite, TCP, or bencode logic: it
  only posts form strings and inspects the wire receipts plus the evidence
  the unchanged fixture already returns."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.fixtures.outbox-json-delivery]
            [jolt.sim.viewer :as viewer]
            [teensyp.client :as client]))

(def ^:private token
  "Test-local loopback capability token (>= 32 characters, per
   jolt.sim.viewer/validate-config!)."
  "outbox-workbench-eval-flow-token-0001")

(def ^:private watchdog-timeout-ms 180000)
(def ^:private request-timeout-ms 120000)

(def ^:private forms
  "The exact four workbench forms from README.md, submitted once each in
   order. Nothing else is evaluated."
  ["(require 'jolt.sim.fixtures.outbox-json-delivery)"
   "(def canonical-run (jolt.sim.fixtures.outbox-json-delivery/exercise-outbox-json-delivery))"
   (str "[(get-in canonical-run [:http :status])\n"
        " (get-in canonical-run [:application :store-state :outbox 0 :status])\n"
        " (get-in canonical-run [:application :delivery :replies 0 \"type\"])]")
   "*1"])

(def ^:private expected-projection [201 :delivered "outbox_delivery_ok"])
(def ^:private expected-printed (pr-str expected-projection))

;; ---- retained progress breadcrumbs -------------------------------------------

(defn- nonempty-env [name]
  (let [value (System/getenv name)]
    (when (seq value) value)))

(defn- progress-file []
  (or (nonempty-env "JOLT_SIM_OUTBOX_WORKBENCH_PROGRESS_FILE")
      (str (or (nonempty-env "TMPDIR") "/tmp")
           "/jolt-sim-outbox-workbench-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  ;; Best-effort append-only breadcrumbs for a single test process. This is
  ;; not a crash-safe journal/WAL contract.
  (spit path (str (pr-str record) "\n") :append true))

;; ---- minimal real-loopback HTTP client -----------------------------------------

(defn- copy-of-length ^bytes [^bytes src n]
  (let [dest (byte-array n)]
    (System/arraycopy src 0 dest 0 n)
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

(defn- find-header-terminator
  "Byte index of the first \\r\\n\\r\\n in raw, or nil."
  [^bytes raw]
  (let [n (alength raw)]
    (loop [i 0]
      (if (> (+ i 4) n)
        nil
        (if (and (= 13 (bit-and 0xff (aget raw i)))
                 (= 10 (bit-and 0xff (aget raw (+ i 1))))
                 (= 13 (bit-and 0xff (aget raw (+ i 2))))
                 (= 10 (bit-and 0xff (aget raw (+ i 3)))))
          i
          (recur (inc i)))))))

(defn- read-until-eof!
  [connection]
  (let [scratch (byte-array 4096)]
    (loop [chunks []]
      (if-let [length (client/receive-into!
                       connection scratch 0 (alength scratch)
                       {:timeout-ms request-timeout-ms})]
        (recur (conj chunks (copy-of-length scratch length)))
        (concat-byte-arrays chunks)))))

(defn- post-eval!
  "Posts one closed v1 eval command for `form` to the real loopback /api/eval
   endpoint over one fresh teensyp.client connection and returns
   {:status .. :wire ..} with the decoded JSON receipt."
  [port form]
  (let [payload (json/write-str {"version" 1 "form" form})
        request-text
        (str "POST /api/eval HTTP/1.1\r\n"
             "Host: 127.0.0.1:" port "\r\n"
             "Content-Type: application/json\r\n"
             "X-Jolt-Sim-Capability: " token "\r\n"
             "Content-Length: " (alength (.getBytes payload "UTF-8")) "\r\n"
             "Connection: close\r\n"
             "\r\n"
             payload)
        connection (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms request-timeout-ms})]
    (try
      (client/send-all! connection (.getBytes request-text "UTF-8")
                        {:timeout-ms request-timeout-ms})
      (let [raw (read-until-eof! connection)
            terminator (find-header-terminator raw)]
        (when-not terminator
          (throw (ex-info "eval-flow test: /api/eval response missing header terminator"
                          {:raw-length (alength raw)})))
        (let [head (String. ^bytes (copy-of-length raw terminator) "UTF-8")
              body (String. ^bytes
                            (let [start (+ terminator 4)
                                  n (- (alength raw) start)
                                  dest (byte-array n)]
                              (System/arraycopy raw start dest 0 n)
                              dest)
                            "UTF-8")
              status-line (first (string/split head #"\r\n"))
              [_ status] (re-matches #"HTTP/1\.1 (\d{3}) .*" status-line)]
          (when-not status
            (throw (ex-info "eval-flow test: /api/eval response missing status line"
                            {:head head})))
          {:status (parse-long status)
           :wire (json/read-str body)}))
      (finally
        (client/close! connection)))))

;; ---- checks -----------------------------------------------------------------

(def ^:private failures (atom 0))

(defn- check [label expected actual]
  (if (= expected actual)
    (println (str "ok   " label))
    (do
      (swap! failures inc)
      (println (str "FAIL " label
                    "\n  expected: " (pr-str expected)
                    "\n  actual:   " (pr-str actual))))))

(defn- wire-ret [response]
  (some #(when (= "ret" (get % "tag")) %) (get (:wire response) "events")))

(defn- envelope-ret [envelope]
  (some #(when (= :ret (:tag %)) %) (:events envelope)))

(defn run-scenario []
  (let [session (eval-session/start)
        server* (atom nil)]
    (try
      (let [server (viewer/start-eval-session!
                    {:port 0 :capability-token token}
                    session)
            _ (reset! server* server)
            port (:port server)
            _ (println (str "eval-only Ripple on 127.0.0.1:" port))
            ;; Submit the four documented forms once each, in order, through
            ;; the real loopback HTTP endpoint.
            responses (mapv #(post-eval! port %) forms)
            sequences (mapv #(get (:wire %) "sequence") responses)
            rets (mapv wire-ret responses)
            printed-3 (get (nth rets 2) "printedValue")
            printed-4 (get (nth rets 3) "printedValue")
            envelopes (eval-session/recent session)
            raw-3 (:val (envelope-ret (nth envelopes 2)))
            raw-4 (:val (envelope-ret (nth envelopes 3)))
            canonical-var (resolve 'user/canonical-run)
            evidence (when canonical-var @canonical-var)]
        (check "all four /api/eval receipts are HTTP 200"
               [200 200 200 200]
               (mapv :status responses))
        (check "sequence receipts are exactly 0, 1, 2, 3"
               ["0" "1" "2" "3"] sequences)
        (check "sequence receipts are strictly monotonic"
               true
               (every? (fn [[a b]] (< a b))
                       (partition 2 1 (map parse-long sequences))))
        (check "no evaluation reported an exception"
               [false false false false]
               (mapv #(boolean (get % "exception")) rets))
        (check "projection wire value is [201 :delivered \"outbox_delivery_ok\"]"
               expected-printed printed-3)
        (check "projection wire value was not truncated"
               false (boolean (get (nth rets 2) "truncated")))
        (check "*1 wire recall equals the projection"
               printed-3 printed-4)
        (check "session retained the exact raw projection value"
               expected-projection raw-3)
        (check "session retained *1 as the same raw value"
               raw-3 raw-4)
        (check "canonical-run var is bound in the session namespace"
               true (some? canonical-var))
        (check "HTTP server errors are empty"
               [] (get-in evidence [:http :server-errors]))
        (check "receiver server errors are empty"
               [] (get-in evidence [:receiver :server-errors]))
        (check "HTTP command connection closes are idempotent"
               [true false] (get-in evidence [:http :close-results :connection]))
        (check "TCP delivery connection closes are idempotent"
               [true false]
               (get-in evidence [:application :delivery :close-results :connection]))
        {:sequences sequences
         :statuses (mapv :status responses)
         :projection printed-3})
      (finally
        ;; Always stop Ripple and close the session after the completed
        ;; evaluations, including on a failed check or a thrown receipt.
        (when-let [server @server*]
          (viewer/stop! server))
        (eval-session/close! session)))))

(defn -main [& _]
  (let [progress (progress-file)]
    (append-progress! progress {:phase :start :status :running})
    (println (str "outbox-workbench eval-flow progress: " progress))
    (flush)
    (let [worker (future (run-scenario))
          outcome (try
                    {:result (deref worker watchdog-timeout-ms ::timeout)}
                    (catch :default error
                      {:error error}))]
      (cond
        (:error outcome)
        (do
          (append-progress! progress
                            {:phase :error
                             :status :errored
                             :message (ex-message (:error outcome))})
          (println (str "FAILURE: eval-flow test errored: "
                        (ex-message (:error outcome))))
          (println (str "progress: " progress))
          (flush)
          (System/exit 1))

        (= ::timeout (:result outcome))
        (do
          (append-progress! progress
                            {:phase :timeout
                             :status :timed-out
                             :watchdog-timeout-ms watchdog-timeout-ms})
          (println (str "FAILURE: eval-flow test timed out after "
                        watchdog-timeout-ms "ms"))
          (println (str "progress: " progress))
          (flush)
          (System/exit 1))

        (pos? @failures)
        (do
          (append-progress! progress
                            {:phase :finish
                             :status :failed
                             :failures @failures
                             :receipts (:result outcome)})
          (println (str "FAILURE: " @failures " eval-flow checks failed"))
          (println (str "progress: " progress))
          (flush)
          (System/exit 1))

        :else
        (do
          (append-progress! progress
                            {:phase :finish
                             :status :passed
                             :failures 0
                             :receipts (:result outcome)})
          (println "eval-flow test passed")
          (println (str "progress: " progress))
          (flush)
          ;; jolt-http and the fixture load core.async, whose non-daemon
          ;; threads keep the process alive after a successful run.
          (System/exit 0))))))
