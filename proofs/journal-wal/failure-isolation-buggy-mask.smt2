(set-logic QF_LIA)

(declare-const app-kind Int)
(declare-const app-token Int)
(declare-const journal-failure Int)
(declare-const observed-kind Int)
(declare-const observed-token Int)
(declare-const violation Bool)

(assert (= app-kind 1))
(assert (= app-token 117))
(assert (= journal-failure 3))
; Bug: a flush failure replaces the primary application exception.
(assert (= observed-kind (ite (= journal-failure 0) app-kind 2)))
(assert (= observed-token
           (ite (= journal-failure 0) app-token journal-failure)))
(assert (= violation
           (or (not (= observed-kind app-kind))
               (not (= observed-token app-token)))))
(assert violation)
(check-sat)
(get-model)
