(ns perturb.slicecheck
  "THE CONVERGENCE SLICE, EXECUTED — and its acceptance gate.

  `PROGRESSIVE-FORMALISM-DESIGN` §7.2b, after the external review. Four steps
  and one gate:

    1. `perturb.evidence`  — evidence-v1 as a validated value, with the vacuity
                             rule in BOTH directions.
    2. `perturb.semantic`  — a separately versioned semantic-event document and
                             a fold runner contract-identical to
                             `jolt.sim.monitor/run-monitor`. The kernel replay
                             trace is not touched.
    3. `perturb.b63`       — ONE B6 clause re-expressed as a fold, and
                             DIFFERENTIALLY TESTED against `perturb.layer`'s
                             own arm on pass, violation and vacuity controls.
    4.                     — the bounded-schedule arm. Executed separately by
                              `jolt.sim.convergence-b63-test`, because it needs
                              a sim-enabled image and a fresh worker. This
                              local gate still covers steps 1--3 only.

  THE GATE. Proceed only if, on every fixture, the two implementations agree on
  the STATUS and on the EXERCISED COUNT, the evidence value is byte-stable, the
  known failures are preserved, and the monitor returns `:inconclusive` exactly
  where the arm does.

  WHY B6.3. E40 already measured its vacuity, so the `:inconclusive` control the
  gate demands exists already and was not invented for this experiment. The
  fixtures are `perturb.layercheck`'s own, reached through their vars rather
  than rebuilt, because a differential test on two different inputs measures
  nothing."
  (:require [perturb.layer :as lay]
            [perturb.layercheck]
            [perturb.evidence :as ev]
            [perturb.semantic :as sem]
            [perturb.b63 :as b63]))

(def ^:private line
  "========================================================================")

(defn- v [s] (deref (resolve s)))

(defn- banner [s] (println) (println (str "--- " s)))

(defn- name-str [k] (if (keyword? k) (name k) (str k)))

(defn- pad [s n] (subs (str s (apply str (repeat n " "))) 0 n))

(def ^:private scope-of
  "one recorded run of two rungs on one thread over one scripted transport")

;; --- the six fixtures, as (name, decls, recorded) ----------------------------

(defn- fixtures []
  (let [record-layer (v 'perturb.layercheck/record-layer)
        edge         (v 'perturb.layercheck/eof-mapping-edge)
        kg  ((v 'perturb.layercheck/known-good))
        ld  ((v 'perturb.layercheck/laundering))
        cc  ((v 'perturb.layercheck/call-over-call))
        ms  ((v 'perturb.layercheck/multi-shot))]
    [["A  known-good"        [(record-layer (:handler kg) [])]   (:recorded kg)]
     ["B  laundering"        [(record-layer (:handler ld) [])]   (:recorded ld)]
     ["B' edge declared"     [(record-layer (:handler ld) edge)] (:recorded ld)]
     ["C  call-over-call"    [(record-layer (:handler cc) [])]   (:recorded cc)]
     ["D  multi-shot"        [(record-layer (:handler ms) [])]   (:recorded ms)]
     ["G  no declared layer" []                                  (:recorded kg)]]))

;; --- the two implementations -------------------------------------------------

(defn- arm-of
  "`perturb.layer`'s own B6.3 arm — the incumbent."
  [decls recorded]
  (let [r (lay/check decls recorded)]
    (first (filter (fn [a] (= :error-mapping (:perturb.layer/arm a)))
                   (:perturb.layer/arms r)))))

(defn- monitor-of
  "The same clause as a fold over the semantic projection — the candidate."
  [decls recorded]
  (let [pr  (lay/semantic-b63-events decls recorded)
        doc (sem/document (first pr) (second pr))]
    {:doc doc :result (b63/run doc)}))

(defn- arm-status [a]
  (let [st (:perturb.layer/state a)]
    (if (= :violation st) :violation (if (= :inconclusive st) :inconclusive :pass))))

;; --- main --------------------------------------------------------------------

(defn -main [& args]
  (println line)
  (println "perturb.slicecheck — the convergence slice, and its acceptance gate")
  (println line)

  ;; ---- step 1: the evidence value, and BOTH vacuity rules -------------------
  (banner "STEP 1 — evidence-v1, and the two vacuity rules")
  (let [ok (ev/evidence {:perturb.evidence/strength :monitored
                         :perturb.evidence/scope {:covered "a" :of "b"}
                         :perturb.evidence/source #{:simulated}
                         :perturb.evidence/basis "one unit"
                         :perturb.evidence/units {:checked 3 :expected 3}})
        try-invalid
        (fn [label m]
          (let [r (try (do (ev/evidence m) :ACCEPTED)
                       (catch :default e (:perturb.evidence/reason (ex-data e))))]
            (println (str "    " label " -> " (pr-str r)))
            r))]
    (println (str "    valid: " (ev/render ok)))
    (let [a (try-invalid "rule A  strength :monitored, checked 0 "
                         {:perturb.evidence/strength :monitored
                          :perturb.evidence/scope {:covered "a" :of "b"}
                          :perturb.evidence/source #{:simulated}
                          :perturb.evidence/basis "one unit"
                          :perturb.evidence/units {:checked 0 :expected 0}})
          b (try-invalid "rule B  findings 2, checked 0        "
                         {:perturb.evidence/strength :unknown
                          :perturb.evidence/scope {:covered "a" :of "b"}
                          :perturb.evidence/source #{:simulated}
                          :perturb.evidence/basis "one unit"
                          :perturb.evidence/units {:checked 0 :expected 0}
                          :perturb.evidence/findings 2})
          c (try-invalid "basis missing                        "
                         {:perturb.evidence/strength :unknown
                          :perturb.evidence/scope {:covered "a" :of "b"}
                          :perturb.evidence/source #{:simulated}
                          :perturb.evidence/basis ""
                          :perturb.evidence/units {:checked 0 :expected 0}})]
      (println)
      (println "    Rule B is the one the review's formulation permits: `reject")
      (println "    nonzero strength with zero checked units` leaves a checker free")
      (println "    to report FINDINGS with an empty denominator.")

      ;; ---- steps 2+3: the differential test --------------------------------
      (banner "STEPS 2+3 — B6.3: incumbent arm vs. fold over semantic events")
      (println "    fixture              | arm                    | monitor                | agree")
      (println "    ---------------------+------------------------+------------------------+------")
      (let [rows
            (map (fn [f]
                   (let [fname (nth f 0) decls (nth f 1) rec (nth f 2)
                         a    (arm-of decls rec)
                         m    (monitor-of decls rec)
                         as   (arm-status a)
                         ms   (:status (:result m))
                         ax   (:perturb.layer/exercised a)
                         mx   (or (:exercised (:detail (:result m))) 0)
                         agree (and (= as ms) (= ax mx))]
                     (println (str "    " (pad fname 20)
                                   " | " (pad (str (name-str as) " exercised " ax) 22)
                                   " | " (pad (str (name-str ms) " exercised " mx) 22)
                                   " | " (if agree "yes" "NO")))
                     {:name fname :agree agree :arm-status as :mon-status ms
                      :arm-ex ax :mon-ex mx
                      :events (count (:perturb.semantic/events (:doc m)))
                      :arm-ev (ev/from-arm a scope-of)
                      :mon-ev (b63/evidence-of (:result m) scope-of)}))
                 (fixtures))
            rows (vec rows)]

        (banner "EVIDENCE-v1, from both sides, rendered")
        (doseq [r rows]
          (println (str "    " (pad (:name r) 20)
                        " arm     " (ev/render (:arm-ev r))))
          (println (str "    " (pad "" 20)
                        " monitor " (ev/render (:mon-ev r))))
          (println (str "    " (pad "" 20)
                        " projected events: " (:events r)
                        "   byte-stable: "
                        (pr-str (= (ev/render (:arm-ev r)) (ev/render (:mon-ev r)))))))

        ;; ---- the gate -------------------------------------------------------
        (banner "THE ACCEPTANCE GATE")
        (let [all-agree   (every? :agree rows)
              stable      (every? (fn [r] (= (ev/render (:arm-ev r))
                                             (ev/render (:mon-ev r)))) rows)
              has-viol    (some (fn [r] (= :violation (:mon-status r))) rows)
              has-pass    (some (fn [r] (= :pass (:mon-status r))) rows)
              has-inconc  (some (fn [r] (= :inconclusive (:mon-status r))) rows)
              rules       (and (= :vacuous-strength a)
                               (= :vacuity-accounting b)
                               (= :missing-basis c))]
          (println (str "    both vacuity rules reject              : " (pr-str rules)))
          (println (str "    every fixture agrees (status+count)    : " (pr-str all-agree)))
          (println (str "    evidence byte-stable across both sides : " (pr-str stable)))
          (println (str "    a VIOLATION control was exercised      : " (pr-str (boolean has-viol))))
          (println (str "    a PASS control was exercised           : " (pr-str (boolean has-pass))))
          (println (str "    an INCONCLUSIVE control was exercised  : " (pr-str (boolean has-inconc))))

          (banner "RESIDUALS, NAMED")
           (println "    1. STEP 4 IS A SEPARATE FRESH-WORKER GATE.")
           (println "       `jolt.sim.convergence-b63-test` runs one exact [1 0] schedule")
           (println "       per selected fixture, then stores the returned semantic-monitor")
           (println "       decision in Case/Outcome v1. This gate does not rerun it.")
           (println "    2. THE REPLAY-RUNNER LIFT REMAINS REJECTED, NOT BY CONSTRUCTION.")
           (println "       perturb.semantic/run-monitor mirrors jolt.sim.monitor's spec,")
           (println "       step-result and decision shapes, but its semantic document is")
           (println "       rejected by jolt.sim.monitor before callbacks. The unary B6.3")
           (println "       factory reconciles declarations for the semantic runner only.")
           (println "    3. ONE CLAUSE, SIX FIXTURES. B6.3 only. Nothing here says the")
           (println "       other five clauses project, and B6.1's credit fold is the one")
           (println "       with an ordering hazard (E35 finding 3).")
           (println "    4. THE PROJECTION IS LOSSY BY DESIGN and drops every event B6.3")
          (println "       does not adjudicate. That is what makes it a test of the")
          (println "       clause rather than of the trace.")

          (println)
          (println line)
          (if (and rules all-agree stable has-viol has-pass has-inconc)
            (do (println "SLICE GATE PASS — the same clause, over the incumbent index and over")
                (println "                  a semantic projection, agrees on status and on")
                (println "                  denominator on every fixture, with a pass, a")
                (println "                  violation and an inconclusive control all")
                 (println "                  exercised. Read the residuals: step 4 is a separate")
                 (println "                  fresh-worker gate; replay-runner lifting remains rejected.")
                (println line))
            (do (println "SLICE GATE FAIL — see the table above.")
                (println line)
                (System/exit 1))))))))
