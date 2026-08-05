(ns jolt.sim.fixtures.outbox-json-delivery
  "Ordinary Jolt whole-application fixture for one committed outbox delivery
  whose HTTP command phase is the existing jolt.example.outbox.http-json
  facade behind the same real or hermetic jolt-http server infrastructure.

  This namespace owns the JSON HTTP command-phase seam consumed by the
  unchanged jolt.sim.fixtures.outbox-delivery flow, plus a two-request command
  phase that proves exact replay or conflicting request-id reuse before the
  same delivery boundary:

  - json-request-bytes builds POST /v1/entities/:entity-id/commands/:request-id
    with Content-Type application/json and the closed {\"payload\" [...]} body
    the facade accepts.
  - json-handler-for wraps the production facade handler over the open
    connection and captures the same bounded command evidence the bencode seam
    captures: the canonical result validated from the exact 201/200 response
    envelope and the emitted pending row projected from that result and the
    command payload (empty for a 200 replay). The shared flow validates this
    projection against its existing ordinary post-COMMIT load-state before it
    can authorize delivery.
  - decode-json-exact parses the raw HTTP response body as exactly one JSON
    value with trailing data rejected.

  The facade, the durable SQLite adapter, the framed TCP/bencode delivery
  receiver/client, acknowledgement validation, ack-gated durable marking, the
  final reload, and every cleanup assertion are the existing unchanged
  implementations shared with the bencode lane. Because the facade drives the
  same jolt.example.outbox.sqlite/apply-command! transaction path with the
    same canonical command, the durable transcript is the existing exact-plan
    projection: command COMMIT, the post-COMMIT pending reload, the
    mark-delivered! transaction, and the final reload (see
    jolt.sim.fixtures.outbox-sqlite-plans/json-delivery-statement-plans).

  The facade percent-decodes the two route parameters, so this fixture's
  request builder deliberately fails closed on any identifier that is not
  already RFC 3986 unreserved ASCII: constructing percent escapes for
  generated identifiers belongs to this lane's bounded Hegel campaign rather
  than request repair. The two-request workload reuses one production facade
  handler and SQLite connection across two ordinary jolt-http request cycles,
  snapshots durable state before and after request two, and then delegates
  delivery unchanged. Concurrency, crash durability,
  exactly-once delivery, and close/reopen semantics remain exactly what the
  unchanged flow does and does not claim. This namespace itself has no
  simulator dependency: whether the process's native socket and SQLite foreign
  calls reach the host or a simulated FFI world remains the caller's choice,
  exactly as for the bencode fixture lanes."
  (:require [clojure.data.json :as json]
            [jolt.example.outbox.http-json :as json-facade]
            [jolt.example.outbox.sqlite :as store]
            [jolt.sim.fixtures.http-sqlite :as http-fixture]
            [jolt.sim.fixtures.outbox-delivery :as delivery]))

(def default-command
  "The JSON lane commits the same canonical command as the bencode lane, so
   the durable transcript, the exact SQLite plans, and every downstream
   delivery artifact are shared byte-for-byte. Must stay equal to
   jolt.sim.fixtures.outbox-delivery/default-command."
  delivery/default-command)

(defn- fail! [reason detail]
  (throw
   (ex-info
    (str "outbox-json-delivery fixture: " (name reason))
    {:type :jolt.sim.fixtures.outbox-json-delivery/invalid-flow
     :reason reason
     :detail detail})))

;; ---- request construction ----------------------------------------------------

(defn- unreserved-ascii?
  "True when `s` is a non-empty string of RFC 3986 unreserved ASCII characters
   only, so it can be placed in a path segment literally with no percent
   encoding. A literal percent, plus, slash, space, or non-ASCII scalar is
   rejected rather than encoded: the facade takes a decoded identifier
   literally, so this fixture never constructs escapes."
  [s]
  (and (string? s)
       (not (empty? s))
       (every? (fn [c]
                 (or (<= (int \a) (int c) (int \z))
                     (<= (int \A) (int c) (int \Z))
                     (<= (int \0) (int c) (int \9))
                     (contains? #{\- \_ \. \~} c)))
               s)))

(defn- octet? [x]
  (and (integer? x) (<= 0 x) (<= x 255)))

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (do
          (System/arraycopy chunk 0 out offset (alength chunk))
          (recur (rest remaining) (+ offset (alength chunk))))
        out))))

(defn- json-request-bytes
  "Builds the exact raw HTTP/1.1 request byte array for one canonical command:
   POST /v1/entities/<entity-id>/commands/<request-id>, Host, Content-Type
   application/json, Content-Length, Connection close, and the closed
   {\"payload\" [...]} JSON body the facade accepts. Fails closed on any
   identifier needing percent encoding and on any non-octet payload element;
   the request is never repaired."
  [command host port]
  (when-not (and (map? command)
                 (= #{:request-id :entity-id :payload} (set (keys command))))
    (fail! :invalid-command {:value command}))
  (let [{:keys [request-id entity-id payload]} command]
    (when-not (unreserved-ascii? entity-id)
      (fail! :unsupported-entity-id {:value entity-id}))
    (when-not (unreserved-ascii? request-id)
      (fail! :unsupported-request-id {:value request-id}))
    (when-not (and (vector? payload) (every? octet? payload))
      (fail! :invalid-payload {:value payload}))
    (let [body (.getBytes (json/write-str {"payload" payload}) "UTF-8")
          head (.getBytes
                (str "POST /v1/entities/" entity-id "/commands/" request-id
                     " HTTP/1.1\r\n"
                     "Host: " host ":" port "\r\n"
                     "Content-Type: application/json\r\n"
                     "Content-Length: " (alength body) "\r\n"
                     "Connection: close\r\n"
                     "\r\n")
                "UTF-8")]
      (concat-byte-arrays [head body]))))

;; ---- response decoding ---------------------------------------------------------

(defn- decode-json-exact
  "Parses the raw HTTP response body as exactly one JSON value: UTF-8 text,
   one complete value, trailing data rejected. Any parse failure is a typed
   fixture violation naming `context`, never a silent partial decode."
  [context ^bytes raw]
  (try
    (json/read-str (String. raw "UTF-8") :extra-data-fn json/on-extra-throw)
    (catch :default error
      (fail! :invalid-json-response
             {:context context
              :body-length (alength raw)
              :message (ex-message error)}))))

;; ---- the seam's handler ---------------------------------------------------------

(def ^:private committed-wire-keys
  #{"status" "request-id" "entity-id" "version" "outbox-id"})

(defn- response->result
  "Validates the facade's closed committed wire envelope from a 201/200
   response and returns the canonical result map. Any other shape or value is
   a typed fixture violation: evidence is never repaired from a response this
   lane cannot name exactly."
  [wire]
  (when-not (and (map? wire) (= committed-wire-keys (set (keys wire))))
    (fail! :invalid-command-response {:wire wire}))
  (when-not (= "committed" (get wire "status"))
    (fail! :invalid-command-response {:wire wire}))
  (let [result {:status :committed
                :request-id (get wire "request-id")
                :entity-id (get wire "entity-id")
                :version (get wire "version")
                :outbox-id (get wire "outbox-id")}]
    (when-not (and (string? (:request-id result))
                   (not (empty? (:request-id result)))
                   (string? (:entity-id result))
                   (not (empty? (:entity-id result)))
                   (integer? (:version result))
                   (<= 1 (:version result))
                   (integer? (:outbox-id result))
                   (<= 1 (:outbox-id result)))
      (fail! :invalid-command-response {:wire wire}))
    result))

(defn- evidence-handler
  "Wraps one production facade handler for one submitted command and publishes
   bounded evidence only when that request was accepted as a fresh commit or
   exact replay. Conflict/rejection responses deliberately publish nothing."
  [facade-handler command command-evidence*]
  (fn [request]
    (let [response (facade-handler request)
          status (:status response)]
      (when (contains? #{200 201} status)
        (let [wire (decode-json-exact :http-command-response-evidence
                                      (.getBytes ^String (:body response)
                                                 "UTF-8"))
              result (response->result wire)
              emitted (if (= 201 status)
                        [{:outbox-id (:outbox-id result)
                          :request-id (:request-id result)
                          :entity-id (:entity-id result)
                          :version (:version result)
                          :payload (:payload command)
                          :status :pending}]
                        [])
              evidence
              {:identities (delivery/semantic-identities result)
               :command {:value command
                         :result result
                         :emitted emitted}}]
          (reset! command-evidence* evidence)))
      response)))

(defn- json-handler-for
  "Builds the seam's handler over the open, schema-initialized connection: the
   production jolt.example.outbox.http-json/command-handler unchanged, plus
   the same bounded command evidence the bencode seam publishes. For a 201/200
   the wrapper validates the exact committed envelope and projects the
   emitted pending row from the result and the command payload (empty for a
   200 replay). The shared flow then validates that projection against its
   ordinary post-COMMIT durable reload; this wrapper does not add a second
   storage read or failure boundary. Any other status publishes nothing, so the
   unchanged flow's expected-status check rejects it without application
   evidence. A facade rejection never throws here; a facade or adapter failure
   propagates to the server's error handler exactly as in the bencode lane.
   The operation-context argument is part of the seam contract and is
   deliberately unused: the production JSON facade has no deadline parameter.
   The shared outer phase boundaries still bracket this HTTP cycle, but this
   lane does not claim the bencode handler's finer pre-command/post-COMMIT
   deadline observations and must not be used for that terminal campaign."
  [conn command command-evidence* _operation-context]
  (evidence-handler (json-facade/command-handler conn)
                    command
                    command-evidence*))

(def json-http-seam
  "The closed HTTP command-phase seam for
   jolt.sim.fixtures.outbox-delivery/exercise-outbox-delivery-via: the routed
   JSON facade handler, the JSON request bytes, the exact 201 fresh-commit
   status, and the exact JSON response decode. A later exact-replay lane uses
   the same handler and decode with expected-status 200."
  {:handler-for json-handler-for
   :request-bytes-for json-request-bytes
   :expected-status 201
   :decode-response (fn [^bytes body] (decode-json-exact :http-response body))})

(defn exercise-outbox-json-delivery
  "Runs the unchanged exercise-outbox-delivery-via whole-application flow with
   the JSON facade command phase: one fresh JSON commit through a real or
   hermetic jolt-http server, the post-COMMIT pending reload through the same
   still-open in-memory SQLite connection, one framed TCP/bencode delivery
   with exact correlated-ack validation, the ack-gated durable
   mark-delivered!, and the final reload. Evidence shape, delivery semantics,
   cleanup behavior, and every nonclaim are exactly the shared flow's; the
   HTTP command phase is the only substituted seam."
  ([]
   (exercise-outbox-json-delivery default-command))
  ([command]
   (exercise-outbox-json-delivery command delivery/expected-ack))
  ([command reply-for]
   (exercise-outbox-json-delivery command reply-for nil))
  ([command reply-for supplied-operation-context]
   (delivery/exercise-outbox-delivery-via
    json-http-seam command reply-for supplied-operation-context)))

;; ---- two-request replay/conflict workload -----------------------------------

(def ^:private workload-keys
  #{:mode :accepted-command :second-command})

(def ^:private workload-modes #{:exact-replay :conflict})

(defn- check-workload!
  [workload]
  (when-not (and (map? workload)
                 (= workload-keys (set (keys workload))))
    (fail! :invalid-workload {:value workload}))
  (let [{:keys [mode accepted-command second-command]} workload]
    ;; Reuse request construction as the closed command/route-safety validator
    ;; without opening a server or touching storage.
    (json-request-bytes accepted-command "127.0.0.1" 1)
    (json-request-bytes second-command "127.0.0.1" 1)
    (when-not (contains? workload-modes mode)
      (fail! :invalid-workload-mode {:value mode}))
    (when-not (= (:request-id accepted-command)
                 (:request-id second-command))
      (fail! :request-id-not-reused {:workload workload}))
    (case mode
      :exact-replay
      (when-not (= accepted-command second-command)
        (fail! :replay-command-mismatch {:workload workload}))

      :conflict
      (when (= accepted-command second-command)
        (fail! :conflict-command-equal {:workload workload})))
    workload))

(defn- http-cycle-evidence
  [http-cycle]
  (let [parsed (:parsed http-cycle)
        body (:body parsed)]
    {:status (:status parsed)
     :content-type (get (:headers parsed) "content-type")
     :content-length (get (:headers parsed) "content-length")
     :body-octets (mapv #(bit-and % 0xff) body)
     :response (decode-json-exact :http-workload-response body)
     :server-errors (:server-errors http-cycle)
     :close-results (:close-results http-cycle)}))

(defn- run-json-cycle
  [facade-handler command command-evidence* operation-context]
  (http-fixture/run-request-cycle
   (evidence-handler facade-handler command command-evidence*)
   (fn [host port]
     (json-request-bytes command host port))
   operation-context))

(defn- conflict-wire
  [request-id]
  {"error" {"type" "request-id-conflict"
            "reason" "request-id-conflict"
            "request-id" request-id}})

(defn- exercise-json-command-workload
  [workload conn operation-context]
  (check-workload! workload)
  (let [{:keys [mode accepted-command second-command]} workload
        facade-handler (json-facade/command-handler conn)
        accepted-evidence* (atom nil)
        second-evidence* (atom nil)
        first-cycle (run-json-cycle facade-handler accepted-command
                                    accepted-evidence* operation-context)
        first-http (http-cycle-evidence first-cycle)
        accepted-evidence @accepted-evidence*]
    (when-not (= 201 (:status first-http))
      (fail! :fresh-command-failed
             {:status (:status first-http)
              :server-errors (:server-errors first-http)}))
    (when-not accepted-evidence
      (fail! :missing-accepted-command-evidence {}))
    (let [state-before-second (store/load-state conn)
          second-cycle (run-json-cycle facade-handler second-command
                                       second-evidence* operation-context)
          second-http (http-cycle-evidence second-cycle)
          second-evidence @second-evidence*
          state-after-second (store/load-state conn)
          accepted-result (get-in accepted-evidence [:command :result])]
      (when-not (= state-before-second state-after-second)
        (fail! :second-request-mutated-state
               {:mode mode
                :before state-before-second
                :after state-after-second}))
      (case mode
        :exact-replay
        (do
          (when-not (= 200 (:status second-http))
            (fail! :replay-status
                   {:status (:status second-http)
                    :server-errors (:server-errors second-http)}))
          (when-not (= (:body-octets first-http) (:body-octets second-http))
            (fail! :replay-body-mismatch
                   {:first (:body-octets first-http)
                    :second (:body-octets second-http)}))
          (when-not (= {:identities (:identities accepted-evidence)
                        :command {:value accepted-command
                                  :result accepted-result
                                  :emitted []}}
                       second-evidence)
            (fail! :invalid-replay-evidence {:value second-evidence})))

        :conflict
        (do
          (when-not (= 409 (:status second-http))
            (fail! :conflict-status
                   {:status (:status second-http)
                    :server-errors (:server-errors second-http)}))
          (when second-evidence
            (fail! :conflict-authorized-delivery
                   {:evidence second-evidence}))
          (when-not (= (conflict-wire (:request-id second-command))
                       (:response second-http))
            (fail! :invalid-conflict-response
                   {:response (:response second-http)}))))
      {:command-evidence accepted-evidence
       ;; The shared flow performs its own canonical pending reload and must
       ;; match this phase-owned final snapshot exactly. This preserves a
       ;; fail-closed boundary even though the workload needs both its own
       ;; before/after-request snapshots.
       :expected-pending-state state-after-second
       :result
       {:http {:requests [first-http second-http]}
        :workload {:mode mode
                   :accepted-command accepted-command
                   :second-command second-command
                   :state-before-second state-before-second
                   :state-after-second state-after-second
                   :second-command-evidence second-evidence
                   :delivery-authorized-command accepted-command}}})))

(defn exercise-outbox-json-replay-conflict
  "Runs two routed JSON requests on one schema-initialized durable application
   state, then delivers and durably marks the sole accepted pending row.

   The first request must commit freshly (201). In :exact-replay mode the
   second command is identical and must return 200 with byte-identical response
   bytes and no emission. In :conflict mode it reuses the request id with a
   different command and must return the exact 409 envelope without publishing
   command evidence. Both modes require byte-equal durable snapshots before
   and after request two, so no second row, version increment, or delivery
   authorization can pass unnoticed. Both requests cross ordinary jolt-http
   request cycles, use one production facade handler and the same SQLite
   connection, and then enter the unchanged delivery/ack/marking flow."
  ([workload]
   (exercise-outbox-json-replay-conflict workload delivery/expected-ack nil))
  ([workload reply-for]
   (exercise-outbox-json-replay-conflict workload reply-for nil))
  ([workload reply-for supplied-operation-context]
   (check-workload! workload)
   (delivery/exercise-outbox-delivery-with-command-phase
    (fn [conn operation-context]
      (exercise-json-command-workload workload conn operation-context))
    reply-for
    supplied-operation-context)))
