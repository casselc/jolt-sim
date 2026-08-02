(set-logic QF_LIA)

; Status encoding: 0 = active, 1 = complete, 2 = failed.
; This non-vacuity witness reaches the exact retry allowance, makes partial
; progress (which resets the consecutive count), tolerates another EINTR, then
; finishes and publishes the frame exactly once.
(declare-const total Int)
(declare-const max-eintr-retries Int)
(declare-const accepted0 Int)
(declare-const accepted1 Int)
(declare-const accepted2 Int)
(declare-const accepted3 Int)
(declare-const accepted4 Int)
(declare-const accepted5 Int)
(declare-const position0 Int)
(declare-const position1 Int)
(declare-const position2 Int)
(declare-const position3 Int)
(declare-const position4 Int)
(declare-const position5 Int)
(declare-const retries0 Int)
(declare-const retries1 Int)
(declare-const retries2 Int)
(declare-const retries3 Int)
(declare-const retries4 Int)
(declare-const retries5 Int)
(declare-const status0 Int)
(declare-const status1 Int)
(declare-const status2 Int)
(declare-const status3 Int)
(declare-const status4 Int)
(declare-const status5 Int)
(declare-const outcome0 Int)
(declare-const outcome1 Int)
(declare-const outcome2 Int)
(declare-const outcome3 Int)
(declare-const outcome4 Int)

(assert (= total 4))
(assert (= max-eintr-retries 2))
(assert (= accepted0 100))
(assert (= position0 0))
(assert (= retries0 0))
(assert (= status0 0))
(assert (= outcome0 0))
(assert (= outcome1 0))
(assert (= outcome2 2))
(assert (= outcome3 0))
(assert (= outcome4 2))

; EINTR one: still active, one retry consumed, no publish.
(assert (= position1
           (ite (= outcome0 0)
                position0
                (+ position0 outcome0))))
(assert (= retries1 (ite (= outcome0 0) (+ retries0 1) 0)))
(assert (= status1
           (ite (= outcome0 0)
                (ite (> (+ retries0 1) max-eintr-retries) 2 0)
                (ite (= position1 total) 1 0))))
(assert (= accepted1
           (ite (= status1 1) (+ accepted0 total) accepted0)))
(assert (= position1 position0))
(assert (= retries1 1))
(assert (= status1 0))
(assert (= accepted1 100))

; EINTR two: exactly at the allowance, so still active.
(assert (= position2
           (ite (= outcome1 0)
                position1
                (+ position1 outcome1))))
(assert (= retries2 (ite (= outcome1 0) (+ retries1 1) 0)))
(assert (= status2
           (ite (= outcome1 0)
                (ite (> (+ retries1 1) max-eintr-retries) 2 0)
                (ite (= position2 total) 1 0))))
(assert (= accepted2
           (ite (= status2 1) (+ accepted1 total) accepted1)))
(assert (= position2 position1))
(assert (= retries2 max-eintr-retries))
(assert (= status2 0))
(assert (= accepted2 100))

; A two-byte partial write advances the offset and resets the consecutive
; retry count rather than carrying the old interruptions forward.
(assert (= position3
           (ite (= outcome2 0)
                position2
                (+ position2 outcome2))))
(assert (= retries3 (ite (= outcome2 0) (+ retries2 1) 0)))
(assert (= status3
           (ite (= outcome2 0)
                (ite (> (+ retries2 1) max-eintr-retries) 2 0)
                (ite (= position3 total) 1 0))))
(assert (= accepted3
           (ite (= status3 1) (+ accepted2 total) accepted2)))
(assert (= position3 2))
(assert (= retries3 0))
(assert (= status3 0))
(assert (= accepted3 100))

; A new interruption after progress starts a fresh consecutive run.
(assert (= position4
           (ite (= outcome3 0)
                position3
                (+ position3 outcome3))))
(assert (= retries4 (ite (= outcome3 0) (+ retries3 1) 0)))
(assert (= status4
           (ite (= outcome3 0)
                (ite (> (+ retries3 1) max-eintr-retries) 2 0)
                (ite (= position4 total) 1 0))))
(assert (= accepted4
           (ite (= status4 1) (+ accepted3 total) accepted3)))
(assert (= position4 position3))
(assert (= retries4 1))
(assert (= status4 0))
(assert (= accepted4 100))

; The final two-byte write completes the frame, resets retries, and advances
; accepted-end by the full four-byte frame exactly once.
(assert (= position5
           (ite (= outcome4 0)
                position4
                (+ position4 outcome4))))
(assert (= retries5 (ite (= outcome4 0) (+ retries4 1) 0)))
(assert (= status5
           (ite (= outcome4 0)
                (ite (> (+ retries4 1) max-eintr-retries) 2 0)
                (ite (= position5 total) 1 0))))
(assert (= accepted5
           (ite (= status5 1) (+ accepted4 total) accepted4)))
(assert (= position5 total))
(assert (= retries5 0))
(assert (= status5 1))
(assert (= accepted5 104))
(check-sat)
(get-model)
