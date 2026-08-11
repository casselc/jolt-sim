(ns jolt.sim.fixtures.broadcast-retained
  "Retained-worker adapter for the interactive real Broadcast cluster.

  File-mailbox protocol mechanics stay in jolt.sim.retained-worker. This
  adapter owns one broadcast-live lifecycle for the command loop and exposes a
  closed command language. It does not use Session or flow-effect because the
  underlying node/application closures are deliberately live and mutable."
  (:require [jolt.maelstrom.fixtures.broadcast-live :as live]
            [jolt.sim.trace :as trace]))

(def ^:private command-keys
  {:inspect #{:op}
   :bootstrap #{:op}
   :step #{:op :node-id}
   :heal #{:op}
   :retry #{:op}
   :read #{:op}
   :stop #{:op}})

(defn- fail! [reason data]
  (throw
   (ex-info "retained Broadcast command is malformed"
            (assoc data :type ::invalid-command :reason reason))))

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
    (trace/canonical-value command [:retained-broadcast :command])
    command))

(defn- continued [operation result application]
  {:terminal? false
   :value {:operation operation
           :result result
           :snapshot (live/snapshot! application)}})

(defn- dispatch [application command]
  (let [{:keys [op node-id]} (checked-command command)]
    (case op
      :inspect
      {:terminal? false :value (live/snapshot! application)}

      :bootstrap
      (continued op (live/bootstrap! application) application)

      :step
      (continued op (live/step! application node-id) application)

      :heal
      (continued op (live/heal! application) application)

      :retry
      (continued op (live/retry! application) application)

      :read
      (continued op (live/read! application) application)

      :stop
      (let [owner? (live/stop! application)]
        {:terminal? true
         :value {:operation :stop
                 :owner? owner?
                 :snapshot (live/snapshot! application)}}))))

(defn ^{:jolt.sim/retained-adapter true}
  run!
  "Starts one exact Broadcast input and serves commands until terminal stop."
  [{:keys [input serve!] :as control}]
  (when-not (and (map? control)
                 (= #{:input :serve!} (set (keys control)))
                 (fn? serve!))
    (fail! :invalid-control {:value-class (str (class control))}))
  (let [application (live/start! input)]
    (try
      (serve! #(dispatch application %))
      (finally
        ;; The terminal command normally owns the transition. This remains
        ;; idempotent if protocol failure or a future terminal command unwinds
        ;; the worker first.
        (live/stop! application)))))
