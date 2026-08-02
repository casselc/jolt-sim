(set-option :produce-unsat-cores true)
(set-logic QF_LIA)

(declare-const accepted0 Int)
(declare-const synced0 Int)
(declare-const frame-length Int)
(declare-const write-complete Bool)
(declare-const power-mode Bool)
(declare-const sync-complete Bool)
(declare-const accepted1 Int)
(declare-const synced1 Int)
(declare-const barrier-complete Bool)
(declare-const violation Bool)

(assert (! (and (<= 0 synced0) (<= synced0 accepted0))
           :named prior-offset-order))
(assert (! (and (<= 1 frame-length) (<= frame-length 16))
           :named frame-length-domain))
(assert (! (= accepted1
              (ite write-complete
                   (+ accepted0 frame-length)
                   accepted0))
           :named accepted-transition))
(assert (! (= barrier-complete
              (and power-mode write-complete sync-complete))
           :named barrier-def))
(assert (! (= synced1
              (ite barrier-complete accepted1 synced0))
           :named synced-transition))
(assert (! (= violation
              (or (< accepted1 accepted0)
                  (< synced1 synced0)
                  (> synced1 accepted1)
                  (and (not barrier-complete)
                       (not (= synced1 synced0)))
                  (and barrier-complete
                       (not (= synced1 accepted1)))))
           :named violation-def))
(assert (! violation :named query))
(check-sat)
(get-unsat-core)
