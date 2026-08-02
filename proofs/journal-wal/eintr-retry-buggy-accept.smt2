(set-logic QF_LIA)

; Deliberately buggy control: with a retry allowance of two, the third
; consecutive EINTR exhausts the budget.  This implementation incorrectly
; treats exhaustion as successful completion and publishes an unwritten frame.
(declare-const total Int)
(declare-const max-eintr-retries Int)
(declare-const accepted0 Int)
(declare-const accepted3 Int)
(declare-const position0 Int)
(declare-const position1 Int)
(declare-const position2 Int)
(declare-const position3 Int)
(declare-const retries0 Int)
(declare-const retries1 Int)
(declare-const retries2 Int)
(declare-const retries3 Int)
(declare-const status0 Int)
(declare-const status1 Int)
(declare-const status2 Int)
(declare-const status3 Int)
(declare-const violation Bool)

(assert (= total 8))
(assert (= max-eintr-retries 2))
(assert (= accepted0 100))
(assert (= position0 0))
(assert (= retries0 0))
(assert (= status0 0))

; First and second EINTR consume the allowed retries without byte progress.
(assert (= position1 position0))
(assert (= retries1 (+ retries0 1)))
(assert (= status1 0))
(assert (= position2 position1))
(assert (= retries2 (+ retries1 1)))
(assert (= status2 0))

; Third EINTR exceeds the allowance.  The position still does not move, but
; the bug transitions to complete and advances accepted-end instead of failing.
(assert (= position3 position2))
(assert (= retries3 (+ retries2 1)))
(assert (> retries3 max-eintr-retries))
(assert (= status3 1))
(assert (= accepted3 (+ accepted0 total)))
(assert (= violation
           (and (= position3 0)
                (< position3 total)
                (= status3 1)
                (> accepted3 accepted0))))
(assert violation)
(check-sat)
(get-model)
