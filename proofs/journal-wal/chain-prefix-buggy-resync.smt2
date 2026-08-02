(set-logic QF_UF)

(declare-const valid0 Bool)
(declare-const valid1 Bool)
(declare-const valid2 Bool)
(declare-const accept0 Bool)
(declare-const accept1 Bool)
(declare-const accept2 Bool)
(declare-const prefix-hole Bool)

; Bug: scan for and accept each later valid-looking record independently.
(assert (= accept0 valid0))
(assert (= accept1 valid1))
(assert (= accept2 valid2))
(assert valid0)
(assert (not valid1))
(assert valid2)
(assert (= prefix-hole
           (and accept2 (or (not accept0) (not accept1)))))
(assert prefix-hole)
(check-sat)
(get-model)
