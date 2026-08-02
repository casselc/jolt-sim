(set-logic QF_LIA)

(declare-const header-complete Bool)
(declare-const magic-valid Bool)
(declare-const version-valid Bool)
(declare-const length-valid Bool)
(declare-const crc-valid Bool)
(declare-const buggy-header-valid Bool)
(declare-const cursor Int)
(declare-const accepted-corrupt-header Bool)

; Bug: the complete header is trusted without checking its CRC.
(assert (= buggy-header-valid
           (and header-complete magic-valid version-valid length-valid)))
(assert header-complete)
(assert magic-valid)
(assert version-valid)
(assert length-valid)
(assert (not crc-valid))
(assert (= cursor (ite buggy-header-valid 36 0)))
(assert (= accepted-corrupt-header
           (and (> cursor 0) (not crc-valid))))
(assert accepted-corrupt-header)
(check-sat)
(get-model)
