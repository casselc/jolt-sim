(ns jolt.sim.eval-stream
  "Socket-free, UI-neutral, prepl-compatible-subset adapter over the public
  project-local jolt.sim.eval-engine API.

  This namespace is deliberately narrow. It does not own a socket, a REPL
  loop, tap lifecycle, stdin, multiform reading, request IDs, interruption,
  remote sessions, browser code, DAP, persistence, or any streaming-during-
  evaluation claim. It evaluates exactly one form through eval-engine/evaluate
  with stdout and stderr capture enabled, records history once through
  eval-engine/record-history!, and emits a prepl-compatible subset of events
  through a caller-supplied emit! callback.

  API:
    evaluate!  evaluates one request and emits {:tag :out ...}, {:tag :err
               ...}, then exactly one terminal {:tag :ret ...} event through
               emit!, returning that terminal :ret event.

  Request map:
    :form                  required string, the exact form to evaluate.
    :ns                    optional string namespace to evaluate in.
    :history               optional :thread, :root, or nil (default); passed
                           unchanged to eval-engine/record-history!. :thread is
                           for a REPL/session owner that has already installed
                           dynamic *1/*2/*3/*e bindings on the calling thread.
    :allow-unresolved-vars? optional boolean (default false).

  The request and the emit! callback are validated before any evaluation
  happens; a malformed request throws a typed ex-info
  ({:type :jolt.sim.eval-stream/invalid-request}) and the engine is never
  called. If emit! throws, the exception propagates and no further event is
  emitted, so a failing emitter never causes a duplicate terminal event."
  (:require [jolt.sim.eval-engine :as eval]))

(def ^:private history-modes #{:thread :root nil})

(defn- invalid-request
  "Typed, bounded ex-info for a malformed evaluation request or emitter."
  [reason data]
  (ex-info
   "jolt-sim eval-stream rejected malformed evaluation request"
   (merge {:type :jolt.sim.eval-stream/invalid-request
           :reason reason}
          data)))

(defn- validate-request!
  "Validates the request map and the emit! callback before any evaluation.
  Throws a typed ex-info on the first violation. Returns the request."
  [request emit!]
  (when-not (map? request)
    (throw (invalid-request :request-not-a-map
                            {:value-class (str (class request))})))
  ;; Keywords, maps, sets, and vectors are IFn values too, but accepting one
  ;; here would silently discard or reinterpret events rather than deliver
  ;; them to an emitter callback.
  (when-not (fn? emit!)
    (throw (invalid-request :emit-not-a-function
                            {:value-class (str (class emit!))})))
  (let [form (:form request)]
    (when-not (string? form)
      (throw (invalid-request :form-not-a-string
                              {:value-class (str (class form))}))))
  (let [ns (:ns request)]
    (when (and (some? ns) (not (string? ns)))
      (throw (invalid-request :ns-not-a-string
                              {:value-class (str (class ns))}))))
  (let [history (get request :history nil)]
    (when-not (contains? history-modes history)
      (throw (invalid-request :history-not-supported
                              {:value-class (str (class history))}))))
  (let [allow? (:allow-unresolved-vars? request)]
    (when (and (contains? request :allow-unresolved-vars?)
               (not (boolean? allow?)))
      (throw (invalid-request :allow-unresolved-vars-not-boolean
                              {:value-class (str (class allow?))}))))
  request)

(defn- throwable-data
  "Reference Throwable->map plus Jolt's catch-site host backtrace.

  Jolt Throwable->map intentionally has an empty JVM-style :trace because a
  thrown value does not retain the Chez continuation. The eval engine captures
  the useful host backtrace at the catch site, so preserve it as namespaced
  data."
  [t backtrace]
  (cond-> (Throwable->map t)
    (some? backtrace) (assoc :jolt/backtrace backtrace)))

(defn evaluate!
  "Evaluates one form through the project-local eval engine and emits a
  prepl-compatible subset of events through emit!. Returns the terminal :ret
  event.

  Calls eval-engine/evaluate exactly once with both stdout and stderr capture
  enabled, then eval-engine/record-history! once with the resolved :history mode.
  Emits nonempty captured stdout as {:tag :out :val string}, then nonempty
  captured stderr as {:tag :err :val string}, then exactly one terminal
  {:tag :ret :val .. :ns .. :ms .. :form ..} event (with :exception true only
  for errors). The terminal :ret event is returned.

  The request and emit! are validated before evaluation; a malformed request
  throws a typed ex-info and the engine is never called. If emit! throws, the
  exception propagates and no further event is emitted."
  [request emit!]
  (validate-request! request emit!)
  (let [form (:form request)
        ns (:ns request)
        history (get request :history nil)
        allow? (boolean (:allow-unresolved-vars? request))
        result (eval/evaluate {:code form
                               :ns ns
                               :allow-unresolved-vars? allow?
                               :capture-out? true
                               :capture-err? true})]
    (eval/record-history! history result)
    (let [out (:out result)
          err (:err result)
          error? (= :error (:status result))
          ret (cond-> {:tag :ret
                       :val (if error?
                              (throwable-data (:exception result)
                                              (:backtrace result))
                              (:value result))
                       :ns (:ns result)
                       :ms (:ms result)
                       :form form}
                error? (assoc :exception true))]
      (when (and (some? out) (not (empty? out)))
        (emit! {:tag :out :val out}))
      (when (and (some? err) (not (empty? err)))
        (emit! {:tag :err :val err}))
      (emit! ret)
      ret)))
