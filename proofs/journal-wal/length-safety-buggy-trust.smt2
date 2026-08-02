(set-logic QF_LIA)

(declare-const declared Int)
(declare-const remaining Int)
(declare-const max-payload Int)
(declare-const trailer Int)
(declare-const bug-kind Int)
(declare-const accept Bool)
(declare-const allocated Int)
(declare-const unsafe-allocation Bool)

(assert (= max-payload 8))
(assert (= trailer 4))
(assert (or (= bug-kind 0) (= bug-kind 1)))
; kind 0 trusts any nonnegative length; kind 1 checks max but not remaining.
(assert (= accept
           (ite (= bug-kind 0)
                (>= declared 0)
                (and (>= declared 0) (<= declared max-payload)))))
(assert (= allocated (ite accept declared 0)))
(assert (= unsafe-allocation
           (and accept
                (or (> allocated max-payload)
                    (> (+ allocated trailer) remaining)))))
(assert (ite (= bug-kind 0)
             (and (= declared 9) (= remaining 13))
             (and (= declared 8) (= remaining 11))))
(assert unsafe-allocation)
(check-sat)
(get-model)
