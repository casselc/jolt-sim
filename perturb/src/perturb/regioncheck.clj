(ns perturb.regioncheck
  "THE DRIVER-B-UNDER-REGIONS EXPERIMENT.

  `PROGRESSIVE-FORMALISM-DESIGN.md` §3.4(d) wrote down a falsifiable prediction:
  driver B's rejected functions move from REJECTED to ACCEPTED at `:monitored`,
  the octets stay identical to driver B's, and a leaked connection is reported
  as a non-empty region exit WITH A COUNT rather than as a name the checker
  cannot track. If the rejections survive, regions do not solve §4.6.

  This namespace runs it. It prints, in order:

    1. the BASELINE — which `perturb.evt` functions the checker rejects today,
       reproduced here rather than quoted, so the comparison is to a run;
    2. the VERDICTS on `perturb.region` and `perturb.evtregion`;
    3. driver B and driver B' side by side on the same fixtures, with the
       octets compared;
    4. driver B' under `:refuse`, which is the same run with the region allowed
       to say no;
    5. the LEAK CONTROL.

  IT IS NOT A GATE. Like `perturb.evtcheck` it records no expectations about
  the verdicts — the point is to find out what the checker says. It exits
  non-zero only if the code fails to RUN, or if driver B' and driver B disagree
  about the octets under `:report`, because that comparison is the experiment's
  own control and a difference there would mean the two drivers are not
  comparable at all."
  (:require [perturb.ir :as pir]
            [perturb.check :as chk]
            [perturb.effect :as fx]
            [perturb.octet :as o]
            [perturb.cap :as cap]
            [perturb.script :as script]))

(def ^:private line
  "========================================================================")

(defn- banner [s] (println) (println (str "--- " s)))

(defn- v [s] (deref (resolve s)))

;; --- fixtures, identical to perturb.evtcheck's --------------------------------

(defn- request-octets [method target body]
  (o/encode-utf8
    (str method " " target " HTTP/1.1\r\nhost: perturb\r\n"
         "content-length: " (count body) "\r\n\r\n" body)))

(defn- reqs [& rs] (reduce (fn [a r] (o/oconcat a r)) o/empty-octets rs))

(def b-conn-0 (reqs (request-octets "GET" "/wait" "")
                    (request-octets "GET" "/count" "")))
(def b-conn-1 (reqs (request-octets "GET" "/count" "")
                    (request-octets "GET" "/bye" "")))

;; --- verdicts ---------------------------------------------------------------

(defn- report-ns
  "-> {:checked n :total n :rejected [{:var v :kinds [...]}]}"
  [sp ns-name lines]
  (println)
  (println (str "== " ns-name " ============================================="))
  (doseq [l lines] (println (str "   " l)))
  (println)
  (let [results (chk/check-namespace! sp ns-name)
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
                  " functions in " ns-name " were CHECKED; " (count bad) " rejected."))
    ;; THE VACUITY GUARD, and it is here because this harness ALREADY TRIPPED IT.
    ;; `perturb.ir` analyses a namespace only if `install!` ran before it was
    ;; loaded, so a namespace this file `:require`s is captured EMPTY and the
    ;; line above reads "0 of 0 ... 0 rejected" — indistinguishable from a clean
    ;; verdict. An empty capture is not a pass.
    (when (zero? (count results))
      (println (str "  INCONCLUSIVE: perturb.ir captured NO defs for " ns-name
                    " — the checker never saw this code."))
      (println "  (perturb.ir analyses a namespace only if install! ran BEFORE it")
      (println "   was loaded; a :require in this file defeats it. Open universe,")
      (println "   by construction — PROGRESSIVE-FORMALISM-DESIGN §5.5.)"))
    {:checked  (count checked)
     :total    (count results)
     :rejected (vec (map (fn [r] {:var (:var r)
                                  :kinds (vec (sort (distinct (map (fn [d] (name (:kind d)))
                                                                   (:diagnostics r)))))})
                         bad))}))

;; --- running ----------------------------------------------------------------

(defn- run-scripted [conns f]
  (let [sess (script/server-session {:conns conns :chunk-size 1})
        r    (fx/with-handlers {'perturb.wire/socket (:handler sess)} (f))]
    {:result r
     :octets (vec (map (fn [i] (script/sent-octets (:state sess) i))
                       (range (count conns))))}))

(defn- show-octets [label ovs]
  (doseq [i (range (count ovs))]
    (println (str "    " label " conn " i "  " (o/ocount (nth ovs i)) " octets"))
    (println (str "      " (o/printable (nth ovs i))))))

(defn- same-octets? [a b]
  (and (= (count a) (count b))
       (every? (fn [i] (= (o/ovec (nth a i)) (o/ovec (nth b i))))
               (range (count a)))))

(defn- show-violations [vs]
  (if (empty? vs)
    (println "    region violations : NONE")
    (do (println (str "    region violations : " (count vs)))
        (doseq [x vs]
          (println (str "      " (name (:perturb.region/kind x))
                        "  " (pr-str (dissoc x :perturb.region/kind))))))))

;; --- main -------------------------------------------------------------------

(defn -main [& args]
  (println line)
  (println "perturb.regioncheck — driver B under regions")
  (println "the falsifiable prediction in PROGRESSIVE-FORMALISM-DESIGN §3.4(d)")
  (println line)

  (pir/capture! ['perturb.evt 'perturb.evtapp 'perturb.region 'perturb.evtregion])
  (let [sp (chk/spec)
        rb (report-ns sp "perturb.evt"
             ["THE BASELINE, reproduced rather than quoted. Driver A checks;"
              "driver B is a map from id to ServerConn and does not."])
        ra (report-ns sp "perturb.evtapp"
             ["THE APPLICATION, unchanged. Its two escapes MUST still be"
              "rejected: a region is not a licence to put a capability in"
              "application state."])
        rr (report-ns sp "perturb.region"
             ["THE PRIMITIVE. A region is one tracked capability whose"
              "membership is a run-time fact. Its own operations must check."])
        re (report-ns sp "perturb.evtregion"
             ["DRIVER B'. The same driver, the same application, the same"
              "helpers, the same fixtures — with the table replaced by a region"
              "and nothing else changed."])]

    (banner "THE COMPARISON")
    (println (str "  driver B  rejected : "
                  (pr-str (vec (map :var (:rejected rb))))))
    (println (str "  driver B' rejected : "
                  (pr-str (vec (map :var (:rejected re))))))
    (println (str "  region    rejected : "
                  (pr-str (vec (map :var (:rejected rr))))))
    (println (str "  evtapp    rejected : "
                  (pr-str (vec (map :var (:rejected ra))))))

    ;; --- run driver B, the baseline octets -----------------------------------
    (banner "RUN: driver B (the map), scripted network")
    (cap/reset-ledger!)
    ((v 'perturb.region/reset-violations!))
    (let [b (run-scripted [b-conn-0 b-conn-1]
              (fn [] ((v 'perturb.evt/serve-table) "in-memory" 0 2 2
                      (v 'perturb.evtapp/initial-state)
                      (v 'perturb.evtapp/step))))]
      (println (str "  driver returned  " (pr-str (:result b))))
      (show-octets "B " (:octets b))

      ;; --- run driver B' under :report ---------------------------------------
      (banner "RUN: driver B' (the region) under :report, scripted network")
      (cap/reset-ledger!)
      ((v 'perturb.region/reset-violations!))
      (let [p (run-scripted [b-conn-0 b-conn-1]
                (fn [] ((v 'perturb.evtregion/serve-region) "in-memory" 0 2 2
                        (v 'perturb.evtapp/initial-state)
                        (v 'perturb.evtapp/step)
                        :report)))
            pv (deref (v 'perturb.region/violations))]
        (println (str "  driver returned  " (pr-str (:result p))))
        (show-octets "B'" (:octets p))
        (show-violations pv)
        (let [same (same-octets? (:octets b) (:octets p))]
          (println (str "  B' octets == B octets, BOTH connections : " (pr-str same)))

          ;; --- run driver B' under :refuse -------------------------------
          (banner "RUN: driver B' under :refuse — the region allowed to say no")
          (cap/reset-ledger!)
          ((v 'perturb.region/reset-violations!))
          (let [q (try (run-scripted [b-conn-0 b-conn-1]
                         (fn [] ((v 'perturb.evtregion/serve-region) "in-memory" 0 2 2
                                 (v 'perturb.evtapp/initial-state)
                                 (v 'perturb.evtapp/step)
                                 :refuse)))
                       (catch :default e {:err (str e)}))
                qv (deref (v 'perturb.region/violations))]
            (if (:err q)
              (do (println "  the run was REFUSED, which is the point of this arm:")
                  (println (str "    " (:err q)))
                  (show-violations qv))
              (do (println (str "  driver returned  " (pr-str (:result q))))
                  (show-octets "B'" (:octets q))
                  (show-violations qv)))

            ;; --- the leak control ---------------------------------------
            (banner "THE LEAK CONTROL: two accepted, one closed, region closed")
            (cap/reset-ledger!)
            ((v 'perturb.region/reset-violations!))
            (let [k (try (run-scripted [b-conn-0 b-conn-1]
                           (fn []
                             (let [l ((v 'perturb.http/listen) "in-memory" 0)]
                               ((v 'perturb.evtregion/leaks-one-connection) l :report))))
                         (catch :default e {:err (str e)}))
                  kv (deref (v 'perturb.region/violations))]
              (if (:err k)
                (println (str "  the control did not complete: " (:err k)))
                (println "  the control completed under :report, as designed"))
              (show-violations kv)
              (println)
              (println "  Driver B's map cannot say this. The connection became opaque")
              (println "  inside accept-into-table and the checker's last word on it was")
              (println "  `dangling` at a site that has nothing to do with the leak.")

              ;; --- verdict ------------------------------------------------
              (println)
              (println line)
              (if same
                (do (println "REGIONCHECK COMPLETED — read the three verdict blocks and the")
                    (println "                        comparison above, not this line.")
                    (println line))
                (do (println "REGIONCHECK FAILED — driver B' and driver B disagree about the")
                    (println "                     octets under :report, so the two drivers are")
                    (println "                     not comparable and the experiment is void.")
                    (println line)
                    (System/exit 1))))))))))
