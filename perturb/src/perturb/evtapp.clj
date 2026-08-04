(ns perturb.evtapp
  "THE APPLICATION, as a pure function of state and one event.

      step : (state, event) -> [state', effects]

  This is the re-frame / Elm shape, and it is here to answer one question: if
  every capability is held by a driver, does user-level code check clean with no
  `perturb.cap` annotations at all? `step` and everything it calls hold NO
  capability, carry NO annotation, and require nothing below `perturb.octet`.

  WHAT AN EVENT AND AN EFFECT ARE. Both are ordinary vectors of ordinary data:

    [:opened  id]                                    a connection was accepted
    [:request id request]                            a request was parsed on it
    [:closed  id]                                    it went away

    [:respond id status reason headers body-string]  put a response on it
    [:close   id]                                    close it

  `id` is an INTEGER, not a connection. That substitution is the whole
  experiment: `perturb.http/ServerConn` never appears in this namespace's
  working code, so there is nothing here for the capability rules to be about.

  WHAT IS REAL ABOUT IT. Two routes read state that survives across requests on
  the same connection (`/count` reports a per-connection hit count and a global
  total), and one route DEFERS its response by a round (`/wait`), which is what
  makes the driver's effect list not simply one response per event. `/bye`
  answers and then asks for the connection to be closed. It is small, but it is
  an application and not a stub: it has per-connection state, it can emit zero
  effects for an event, and it can emit three.

  AND `/wait` IS THE RESULT OF THE WHOLE EXPERIMENT, so read it before the
  controls. Deferring a response is the most ordinary thing an event-driven
  application does. Underneath, it makes the driver read a SECOND request from a
  connection that still owes a response — which is
  `perturb.httpcorpus/read-twice-without-responding`, a program `perturb.check`
  REJECTS with `:typestate` when it is written against a capability.
  `perturb.evtcheck` runs it and shows the ledger failing to join up. Nothing in
  this namespace is wrong on purpose, and the declared machine is broken anyway.

  WHAT THIS NAMESPACE DOES NOT ESTABLISH — and this is the point of the four
  programs at the bottom. `step` checking clean is only interesting if a WRONG
  program in the same position would be rejected. Two of the four are rejected;
  two are ACCEPTED and are wrong anyway, and the reason is stated where they
  are. Read those before reading `step`'s verdict as good news.")

(require '[perturb.octet :as o])
;; ONLY the two rejected controls at the bottom use this. `step` does not.
(require '[perturb.http :as h])

;; --- state ------------------------------------------------------------------

(def initial-state
  "Application state. A map from connection id to that connection's own state,
  plus a counter across all of them. Ordinary data — nothing in here has a
  lifetime, which is exactly why the checker has nothing to say about it."
  {:conns {} :total 0})

(defn conn-state
  "This connection's state, or a fresh one. `:owed` holds a response effect that
  `/wait` deferred."
  [state id]
  (let [cs (get (:conns state) id)]
    (if (nil? cs) {:hits 0 :owed nil} cs)))

(defn put-conn [state id cs]
  (assoc state :conns (assoc (:conns state) id cs)))

;; --- routes -----------------------------------------------------------------

(defn route
  "request, this connection's hit count, the global total ->
  [status reason headers body-string].

  Pure, and deliberately shaped like `perturb.http/echo-response`, whose
  docstring is the reason to believe the hypothesis this namespace tests: it
  takes no capability, so the checker reads its body like any other."
  [req hits total]
  (let [t (:target req)]
    (cond
      (= "/count" t)
      [200 "OK" {"content-type" "text/plain"}
       (str "conn-hits=" hits " total=" total)]

      (= "/echo" t)
      [200 "OK" {"content-type" "text/plain"}
       (str (:method req) " " (:target req) " " (o/ocount (:body req)))]

      (= "/wait" t)
      [200 "OK" {"content-type" "text/plain"}
       (str "waited, then conn-hits=" hits)]

      (= "/bye" t)
      [200 "OK" {"content-type" "text/plain"} (str "bye after " hits)]

      :else
      [404 "Not Found" {"content-type" "text/plain"} (str "no route " t)])))

;; --- the step function ------------------------------------------------------

(defn step
  "(state, event) -> [state', effects].

  HOLDS NO CAPABILITY AND CARRIES NO ANNOTATION. It never sees a ServerConn; it
  sees an integer id. Whether that is enough to make it trivially safe is what
  `perturb.evtcheck` measures, and the answer is at the bottom of this file."
  [state event]
  (let [tag (nth event 0)]
    (cond
      (= :opened tag)
      (let [id (nth event 1)]
        [(put-conn state id {:hits 0 :owed nil}) []])

      (= :closed tag)
      (let [id (nth event 1)]
        [(put-conn state id nil) []])

      (= :request tag)
      (let [id    (nth event 1)
            req   (nth event 2)
            cs    (conn-state state id)
            hits  (inc (:hits cs))
            total (inc (:total state))
            owed  (:owed cs)
            r     (route req hits total)
            resp  [:respond id (nth r 0) (nth r 1) (nth r 2) (nth r 3)]
            base  (assoc state :total total)]
        (if (= "/wait" (:target req))
          ;; DEFER. No response this round; it is owed and goes out with the
          ;; next one. This is the case that makes the driver's effect list
          ;; something other than one response per request.
          [(put-conn base id {:hits hits :owed resp})
           (if (nil? owed) [] [owed])]
          (let [pre (if (nil? owed) [] [owed])
                out (conj pre resp)]
            [(put-conn base id {:hits hits :owed nil})
             (if (= "/bye" (:target req)) (conj out [:close id]) out)])))

      :else [state []])))

;; ===========================================================================
;; THE POSITIVE CONTROL
;; ===========================================================================
;;
;; If user-level code holds no capability then nothing user-level can be wrong,
;; and `step checks clean` is vacuous. These four are the control. TWO ARE
;; REJECTED AND TWO ARE NOT, and the two that are not are the finding.

(defn stashes-the-connection-in-app-state
  "MUST BE REJECTED, and is. The application decides it would like to keep the
  connection around — so it takes one and puts it in its own state map.

  `perturb.check`'s abstract domain has exactly one composite, the TUPLE, so a
  capability entering a MAP cannot be followed out and is reported where it
  enters (`:escape`). This is the same shape as
  `perturb.httpcorpus/listener-into-a-map-with-its-connection`.

  Note what it costs to write this control at all: this function has to MINT a
  connection, because an unannotated function's parameters are opaque and a
  capability handed to one is the caller's problem, not this function's. So the
  control is `an app that opens a socket`, not `an app that was given one`."
  [state host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c  (second a)]
    [{:conns {0 c} :total (:total state)} []]))

(defn returns-the-connection-in-an-effect
  "MUST BE REJECTED, and is. The other half of the same idea: the connection is
  not stored, it is handed back to the driver INSIDE an effect, where a driver
  that trusted its effect list would use it.

  A vector IS the tuple the domain models, so the capability is followed all the
  way out — and then rejected at the function boundary, because this function
  declares no `:produces`."
  [state host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c  (second a)]
    [state [[:respond c 200 "OK" {"content-type" "text/plain"} "hi"]]]))

(defn two-responses-for-one-request
  "MUST BE REJECTED AND IS NOT. Read this before believing `step`'s verdict.

  One `:request` event, two `:respond` effects for the same connection.
  `perturb.http/ServerConn`'s machine says a response is legal only from
  `:responding` and returns the connection to `:reading`, so the second
  `respond!` the driver performs is `:reading -> :responding`'s edge taken from
  the wrong state: exactly what
  `perturb.httpcorpus/respond-without-reading` is REJECTED for.

  Written here it is two vectors of keywords and integers. There is no
  capability in it, so there is nothing for the typestate rule to be about, and
  the checker accepts it. `perturb.evtcheck` then RUNS it and shows the driver
  putting two responses for one request on the wire.

  THE BOUNDARY MOVED THE ERROR, IT DID NOT REMOVE IT."
  [state event]
  (if (= :request (nth event 0))
    (let [id (nth event 1)]
      [state [[:respond id 200 "OK" {"content-type" "text/plain"} "first"]
              [:respond id 200 "OK" {"content-type" "text/plain"} "second"]]])
    [state []]))

(defn responds-after-closing
  "MUST BE REJECTED AND IS NOT. The second machine violation, on the other edge:
  close the connection, then write to it. `:closed` is ServerConn's TERMINAL
  state and `perturb.httpcorpus/write-after-finish` is the analogous program on
  ResponseBody — rejected there as `use-after-move`.

  Here the same use-after-move is the integer `id` appearing twice in a vector,
  and an integer may be used as often as it likes."
  [state event]
  (if (= :request (nth event 0))
    (let [id (nth event 1)]
      [state [[:close id]
              [:respond id 200 "OK" {"content-type" "text/plain"} "after close"]]])
    [state []]))
