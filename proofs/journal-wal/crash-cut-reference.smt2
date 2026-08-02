(set-option :produce-unsat-cores true)
(set-logic QF_LIA)

(declare-const n Int)
(declare-const l0 Int)
(declare-const l1 Int)
(declare-const l2 Int)
(declare-const l3 Int)
(declare-const header Int)
(declare-const overhead Int)
(declare-const e0 Int)
(declare-const e1 Int)
(declare-const e2 Int)
(declare-const e3 Int)
(declare-const full-end Int)
(declare-const cut Int)

; Unrolled implementation state: cursor and recovered count after each loop
; iteration. A failed/incomplete step leaves both at the last accepted prefix.
(declare-const header-complete Bool)
(declare-const cursor0 Int)
(declare-const count0 Int)
(declare-const take0 Bool)
(declare-const take1 Bool)
(declare-const take2 Bool)
(declare-const take3 Bool)
(declare-const cursor1 Int)
(declare-const cursor2 Int)
(declare-const cursor3 Int)
(declare-const cursor4 Int)
(declare-const count1 Int)
(declare-const count2 Int)
(declare-const count3 Int)
(declare-const count4 Int)

; Independent closed-form specification.
(declare-const expected Int)
(declare-const expected-last-good Int)
(declare-const partial-accepted Bool)
(declare-const violation Bool)

(assert (! (and (<= 0 n) (<= n 4)) :named record-count-domain))
(assert (! (and (<= 0 l0) (<= l0 8)
                (<= 0 l1) (<= l1 8)
                (<= 0 l2) (<= l2 8)
                (<= 0 l3) (<= l3 8))
           :named payload-domains))
(assert (! (= header 36) :named header-size))
(assert (! (= overhead 28) :named record-overhead))
(assert (! (= e0 (+ header overhead l0)) :named end0))
(assert (! (= e1 (+ e0 overhead l1)) :named end1))
(assert (! (= e2 (+ e1 overhead l2)) :named end2))
(assert (! (= e3 (+ e2 overhead l3)) :named end3))
(assert (! (= full-end
              (ite (= n 0) header
                (ite (= n 1) e0
                  (ite (= n 2) e1
                    (ite (= n 3) e2 e3)))))
           :named full-end-def))
(assert (! (and (<= 0 cut) (<= cut full-end)) :named cut-domain))

; Reference algorithm over a valid encoded segment. Header corruption is a
; separate model; here only crash truncation varies.
(assert (! (= header-complete (<= header cut)) :named header-step))
(assert (! (= cursor0 (ite header-complete header 0)) :named cursor0-def))
(assert (! (= count0 0) :named count0-def))
(assert (! (= take0 (and header-complete (<= 1 n) (<= e0 cut)))
           :named take0-def))
(assert (! (= cursor1 (ite take0 e0 cursor0)) :named cursor1-def))
(assert (! (= count1 (ite take0 1 count0)) :named count1-def))
(assert (! (= take1 (and take0 (<= 2 n) (<= e1 cut)))
           :named take1-def))
(assert (! (= cursor2 (ite take1 e1 cursor1)) :named cursor2-def))
(assert (! (= count2 (ite take1 2 count1)) :named count2-def))
(assert (! (= take2 (and take1 (<= 3 n) (<= e2 cut)))
           :named take2-def))
(assert (! (= cursor3 (ite take2 e2 cursor2)) :named cursor3-def))
(assert (! (= count3 (ite take2 3 count2)) :named count3-def))
(assert (! (= take3 (and take2 (<= 4 n) (<= e3 cut)))
           :named take3-def))
(assert (! (= cursor4 (ite take3 e3 cursor3)) :named cursor4-def))
(assert (! (= count4 (ite take3 4 count3)) :named count4-def))

(assert (! (= expected
              (+ (ite (and (<= 1 n) (<= e0 cut)) 1 0)
                 (ite (and (<= 2 n) (<= e1 cut)) 1 0)
                 (ite (and (<= 3 n) (<= e2 cut)) 1 0)
                 (ite (and (<= 4 n) (<= e3 cut)) 1 0)))
           :named expected-def))
(assert (! (= expected-last-good
              (ite (= expected 4) e3
                (ite (= expected 3) e2
                  (ite (= expected 2) e1
                    (ite (= expected 1) e0
                      (ite (<= header cut) header 0))))))
           :named expected-last-good-def))
(assert (! (= partial-accepted
              (or (and (<= 1 count4) (< cut e0))
                  (and (<= 2 count4) (< cut e1))
                  (and (<= 3 count4) (< cut e2))
                  (and (<= 4 count4) (< cut e3))))
           :named partial-def))
(assert (! (= violation
              (or (not (= count4 expected))
                  (not (= cursor4 expected-last-good))
                  partial-accepted))
           :named violation-def))
(assert (! violation :named query))
(check-sat)
(get-unsat-core)
