(set-logic QF_LIA)

(declare-const app-kind Int)
(declare-const app-token Int)
(declare-const journal-failure Int)
(declare-const app-invoked Bool)
(declare-const observed-kind Int)
(declare-const violation Bool)

(assert (= app-kind 0))
(assert (= app-token 42))
(assert (= journal-failure 1))
; Bug: journal open failure aborts before scenario invocation.
(assert (= app-invoked (= journal-failure 0)))
(assert (= observed-kind (ite app-invoked app-kind 2)))
(assert (= violation
           (or (not app-invoked) (not (= observed-kind app-kind)))))
(assert violation)
(check-sat)
(get-model)
