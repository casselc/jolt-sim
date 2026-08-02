(set-logic QF_LIA)

(declare-const total Int)
(declare-const accepted0 Int)
(declare-const first-write Int)
(declare-const position Int)
(declare-const buggy-accepted Int)
(declare-const incomplete-published Bool)

(assert (= total 8))
(assert (= accepted0 100))
(assert (= first-write 3))
(assert (= position first-write))
; Bug: publish the whole frame after the first positive partial write.
(assert (= buggy-accepted (+ accepted0 total)))
(assert (= incomplete-published
           (and (< position total) (> buggy-accepted accepted0))))
(assert incomplete-published)
(check-sat)
(get-model)
