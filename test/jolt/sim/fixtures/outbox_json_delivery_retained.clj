(ns jolt.sim.fixtures.outbox-json-delivery-retained
  "Retained-worker adapter for the canonical live JSON outbox application.

  The adapter owns one observe-mode controller scope and one unchanged live
  lifecycle for the complete command loop.  File-mailbox mechanics remain in
  jolt.sim.retained-worker; this namespace only translates a small closed
  command vocabulary into the existing application lifecycle operations."
  (:require [jolt.sim.fixtures.outbox-delivery :as delivery]
            [jolt.sim.fixtures.outbox-json-delivery-live :as live]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.trace :as trace]))

(def ^:private command-keys
  {:inspect #{:op}
   :submit #{:op :command}
   :deliver #{:op}
   :stop #{:op}})

(defn- fail! [reason data]
  (throw
   (ex-info "retained live-outbox command is malformed"
            (assoc data
                   :type ::invalid-command
                   :reason reason))))

(defn- checked-command [command]
  (when-not (map? command)
    (fail! :not-a-map {:value-class (str (class command))}))
  (let [op (:op command)
        expected (get command-keys op)]
    (when-not expected
      (fail! :unknown-operation {:operation op}))
    (when-not (= expected (set (keys command)))
      (fail! :wrong-keys
             {:operation op
              :expected expected
              :actual (set (keys command))}))
    (trace/canonical-value command [:retained-outbox :command])
    command))

(defn- hostile-ack [message]
  ;; Preserve correlation while making the acknowledgement semantically
  ;; hostile, so the unchanged delivery validation rejects before marking.
  (assoc (delivery/expected-ack message) :status "rejected"))

(defn- start-config [input]
  (let [input (or input {})]
    (when-not (and (map? input)
                   (every? #{:ack-outcome :retained-evidence} (keys input)))
      (fail! :invalid-input {:value-class (str (class input))}))
    (let [ack-outcome (get input :ack-outcome :accepted)
          retained-evidence (get input :retained-evidence 32)]
    (when-not (contains? #{:accepted :hostile} ack-outcome)
      (fail! :invalid-ack-outcome {:ack-outcome ack-outcome}))
    (cond-> {:retained-evidence retained-evidence}
        (= :hostile ack-outcome) (assoc :reply-for hostile-ack)))))

(defn- dispatch [application command]
  (let [{:keys [op] :as command} (checked-command command)]
    (case op
      :inspect
      {:terminal? false
       :value (live/snapshot! application)}

      :submit
      {:terminal? false
       :value {:operation :submit
               :result (live/submit-command! application (:command command))
               :snapshot (live/snapshot! application)}}

      :deliver
      {:terminal? false
       :value {:operation :deliver
               :result (live/deliver-next! application)
               :snapshot (live/snapshot! application)}}

      :stop
      (let [owner? (live/stop! application)]
        {:terminal? true
         :value {:operation :stop
                 :owner? owner?
                 :snapshot (live/snapshot! application)}}))))

(defn- preserve-primary-with-cleanup [primary cleanup]
  (if cleanup
    (ex-info (or (ex-message primary) (str primary))
             (assoc (or (ex-data primary) {})
                    :jolt.sim.retained/cleanup-error
                    (trace/normalize-error cleanup))
             primary)
    primary))

(defn ^{:jolt.sim/retained-adapter true}
  run!
  "Runs one retained live application until its terminal command.

  `control` is supplied only by jolt.sim.retained-worker and contains restored
  canonical :input plus the child-owned :serve! command-loop closure."
  [{:keys [input serve!] :as control}]
  (when-not (and (map? control)
                 (= #{:input :serve!} (set (keys control)))
                 (fn? serve!))
    (fail! :invalid-control {:value-class (str (class control))}))
  (runtime/run-controlled
   {:ffi-mode :observe}
   (fn []
     (let [application (live/start! (start-config input))
           primary (volatile! nil)]
       (try
         (serve! #(dispatch application %))
         (catch :default error
           (vreset! primary error))
         (finally
           (when @primary
             (let [cleanup
                   (try
                     (live/stop! application)
                     nil
                     (catch :default error error))]
               (throw (preserve-primary-with-cleanup @primary cleanup))))
           ;; The terminal :stop command normally owns cleanup.  If serve!
           ;; ended without an exception after some future terminal command,
           ;; retain idempotent lifecycle ownership here as well.
           (live/stop! application)))))))
