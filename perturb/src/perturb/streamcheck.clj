(ns perturb.streamcheck
  "Run `perturb.check` over `perturb.stream` — code perturb did not write.

  E15 nonclaim 3 says the corpus is written by the same author as the checker,
  against the same reading of §1.2, and that its accept set was wrong for
  exactly that reason. Every artifact checked since has had the same defect.
  This one does not: `perturb.stream` was ported by an agent that never saw the
  rules. Whatever it says here is the first evidence about the rule set that is
  not self-fulfilling.

  THIS IS NOT A GATE. It has no recorded expectations, because the point is to
  find out what the verdicts are, not to assert them."
  (:require [perturb.ir :as pir]
            [perturb.check :as chk]))

(defn -main [& _]
  (pir/capture! ['perturb.stream 'perturb.streamcap])
  (let [sp (chk/spec)]
    (println)
    (println "== perturb.stream, checked ============================================")
    (println "   ported from teensyp.stream by an agent forbidden to read the rules.")
    (println "   The declaration is in perturb.streamcap and the port is unmodified.")
    (println)
    (let [results (chk/check-namespace! sp "perturb.stream")
          ax?     (fn [r] (or (contains? (:primitives sp) (:var r))
                              (contains? (:representation sp) (:var r))))
          checked (remove ax? results)
          bad     (filter (fn [r] (seq (:diagnostics r))) checked)]
      (doseq [r results]
        (println (str "  "
                      (cond (ax? r) "[axiom] "
                            (empty? (:diagnostics r)) "[ok   ] "
                            :else "[NO   ] ")
                      (:var r)
                      (if (or (ax? r) (empty? (:diagnostics r))) ""
                          (str "  " (vec (sort (distinct (map (fn [d] (name (:kind d)))
                                                              (:diagnostics r))))))))))
      (println)
      (doseq [r bad]
        (println (str "  --- " (:var r)))
        (print (chk/render (:diagnostics r))))
      (println (str "  " (count checked) " of " (count results)
                    " functions checked; " (count bad) " rejected."))
      (println)
      (println "  Read the rejections as EVIDENCE ABOUT THE RULES, not about the")
      (println "  code. The port runs — `-M:stream` exits 0 against a real loopback")
      (println "  socket — so every rejection here is the checker refusing a program")
      (println "  that works.")
      (System/exit 0))))
