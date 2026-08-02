(set-logic QF_LIA)

(declare-const total Int)
(declare-const accepted0 Int)
(declare-const p0 Int)
(declare-const p1 Int)
(declare-const p2 Int)
(declare-const accepted1 Int)

; First attempt receives EINTR and advances zero bytes; the retry writes the
; complete frame and advances accepted-end exactly once.
(assert (= total 8))
(assert (= accepted0 100))
(assert (= p0 0))
(assert (= p1 p0))
(assert (= p2 (+ p1 total)))
(assert (= p2 total))
(assert (= accepted1 (+ accepted0 total)))
(assert (= accepted1 108))
(check-sat)
(get-model)
