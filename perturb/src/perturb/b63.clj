(ns perturb.b63
  "B6.3 (error mapping) RE-EXPRESSED as a pure fold over semantic events.

  Step 3 of the convergence slice. B6.3 was chosen because E40 already measured
  its vacuity, so the `:inconclusive` control the acceptance gate demands
  EXISTS ALREADY and did not have to be invented for this experiment.

  THE ORIGINAL IS NOT A FOLD. `perturb.layer/check-error-mapping` builds an
  index and runs a nested loop: for every request a declared layer replied to,
  it filters the refusal list for positions strictly inside that request's
  extent. This namespace is the same rule as a LEFT FOLD over an ordered event
  stream, which is what makes it liftable to `jolt.sim.monitor`.

  WHY THE TWO AGREE, STATED BEFORE IT IS MEASURED. A refusal at position `p`
  belongs to request `r` iff `lo_r < p < hi_r`. Folding in position order, the
  requests satisfying that at the moment `p` arrives are exactly the ones
  OPENED and NOT YET REPLIED. So `:open` carries them, a refusal is attributed
  to all of them, and the decision is deferred to the reply — where the
  original also makes it. `perturb.slicecheck` measures whether the argument
  holds on all six fixtures rather than trusting it.

  THE THREE-STATE RULE IS THE SAME ONE. `:violation` if any unmapped refusal
  was found; `:inconclusive` if the fold adjudicated ZERO (request, refusal)
  pairs; `:pass` otherwise. A trace with no refusal inside any declared
  layer's extent contains no instance of the thing this clause forbids, and
  saying `pass` about it is the defect E40 removed."
  (:require [perturb.semantic :as sem]
            [perturb.evidence :as ev]))

(def basis
  "One sentence. Copied VERBATIM from the arm it must agree with, because a
  differential test between two implementations with two different
  denominators would be comparing nothing."
  (str "one refusal that occurred INSIDE the extent of a request a declared"
       " layer answered [:ok ...] — the absorption B6.3 is about. A trace with"
       " no refusal inside any declared layer's extent contains no instance of"
       " the thing this clause forbids"))

(defn- mapped?
  "Does `layer` declare an error-mapping edge for {op, abort}?"
  [case-m layer op abort]
  (let [edges (get (:layers case-m) layer)]
    (some (fn [e] (and (= op (nth e 0)) (= abort (nth e 1)))) edges)))

(def initial
  {:open       {}     ; id -> {:layer :op}
   :pending    {}     ; id -> [abort ...] refusals seen inside its extent
   :exercised  0
   :violations []
   :dangling   0})    ; refusals attributed to no open declared request

(defn step
  "(state index event) -> {:state state'} — B6.3 never decides early, because a
  violation found at event 3 does not license ignoring the pairs after it, and
  the EXERCISED COUNT is only complete at the end. An early decision would
  return a verdict with a denominator that had stopped counting."
  [st _index e]
  (let [tag (nth e 0)
        id  (nth e 1)]
    (cond
      (= :layer/request-opened tag)
      {:state (assoc st :open (assoc (:open st) id {:layer (nth e 3) :op (nth e 4)}))}

      (= :layer/refusal tag)
      ;; Attribute to EVERY currently-open declared request. `lo < p` holds
      ;; because it was opened earlier in the stream; `p < hi` holds because it
      ;; has not replied yet.
      (let [open-ids (keys (:open st))]
        (if (empty? open-ids)
          ;; MISSING CORRELATION IS NOT A PASS. A refusal inside no declared
          ;; extent is out of this clause's scope, and it is counted so the
          ;; report can say so rather than dropping it silently.
          {:state (assoc st :dangling (inc (:dangling st)))}
          {:state (assoc st :pending
                         (reduce (fn [m oid]
                                   (assoc m oid (conj (or (get m oid) [])
                                                      {:abort (nth e 3) :from id})))
                                 (:pending st) open-ids))}))

      (= :layer/request-replied tag)
      (let [info (get (:open st) id)
            hits (or (get (:pending st) id) [])]
        (if (nil? info)
          ;; A reply for a request this document never opened: the observation
          ;; the monitor requires is ABSENT, so it is recorded, not assumed.
          {:state (assoc st :dangling (inc (:dangling st)))}
          {:state (assoc st
                         :open       (dissoc (:open st) id)
                         :pending    (dissoc (:pending st) id)
                         :exercised  (+ (:exercised st) (count hits))
                         :violations (reduce
                                       (fn [vs h]
                                         (conj vs {:id id :layer (:layer info)
                                                   :op (:op info) :abort (:abort h)
                                                   :from (:from h)}))
                                       (:violations st) hits))}))

      :else {:state st})))

(defn finish
  "(state case) -> decision. The unmapped filter runs HERE because it needs the
  declaration set, which is on the case rather than on any event."
  [st case-m]
  (let [unmapped (filter (fn [v] (not (mapped? case-m (:layer v) (:op v) (:abort v))))
                         (:violations st))
        ex       (:exercised st)]
    (cond
      (seq unmapped)
      {:status :violation
       :detail {:rule :unmapped-refusal :exercised ex
                :count (count unmapped) :dangling (:dangling st)
                :witnesses (vec unmapped)}}

      (zero? ex)
      {:status :inconclusive
       :detail {:exercised 0 :basis basis :dangling (:dangling st)}}

      :else
      {:status :pass :detail {:exercised ex :dangling (:dangling st)}})))

(def spec
  "The monitor, in `jolt.sim.monitor/run-monitor`'s spec shape."
  {:id      :perturb.b63/error-mapping
   :initial initial
   :step    step
   :finish  finish})

(defn run
  "-> {:id :status :detail :index}"
  [doc] (sem/run-monitor spec doc))

(defn evidence-of
  "The monitor's decision as an evidence-v1 value. `:exercised` is the
  denominator on both sides, which is what makes the two comparable."
  [result scope-of]
  (let [d  (:detail result)
        ex (or (:exercised d) 0)
        f  (or (:count d) 0)]
    (ev/evidence
      {:perturb.evidence/strength (if (= :inconclusive (:status result))
                                    :unknown :monitored)
       :perturb.evidence/scope    {:covered "this recorded trace, arm :error-mapping"
                                   :of      scope-of}
       :perturb.evidence/source   #{:simulated}
       :perturb.evidence/basis    basis
       :perturb.evidence/units    {:checked ex :expected ex}
       :perturb.evidence/findings f})))
