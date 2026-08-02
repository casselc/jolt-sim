(set-option :produce-unsat-cores true)
(set-logic QF_BV)

(declare-const declared32 (_ BitVec 32))
(declare-const remaining64 (_ BitVec 64))
(declare-const declared64 (_ BitVec 64))
(declare-const max-payload64 (_ BitVec 64))
(declare-const trailer64 (_ BitVec 64))
(declare-const needed64 (_ BitVec 64))
(declare-const overflow Bool)
(declare-const length-in-range Bool)
(declare-const frame-complete Bool)
(declare-const accept Bool)
(declare-const allocates Bool)
(declare-const allocated64 (_ BitVec 64))
(declare-const unsafe-allocation Bool)
(declare-const violation Bool)

; Decode the wire u32 into a wider unsigned domain before arithmetic.
(assert (! (= declared64 ((_ zero_extend 32) declared32))
           :named zero-extend-before-add))
(assert (! (= max-payload64 #x0000000000010000)
           :named max-payload-fixed))
(assert (! (= trailer64 #x0000000000000004) :named trailer-fixed))
(assert (! (= needed64 (bvadd declared64 trailer64)) :named needed-def))
(assert (! (= overflow (bvult needed64 declared64)) :named overflow-check))
(assert (! (= length-in-range (bvule declared64 max-payload64))
           :named range-check))
(assert (! (= frame-complete (bvule needed64 remaining64))
           :named remaining-check))

; Unrolled scanner decision and allocation transition.
(assert (! (= accept
              (and (not overflow) length-in-range frame-complete))
           :named accept-transition))
(assert (! (= allocates accept) :named allocation-transition))
(assert (! (= allocated64
              (ite allocates declared64 #x0000000000000000))
           :named allocation-size))
(assert (! (= unsafe-allocation
              (and allocates
                   (or overflow
                       (bvugt allocated64 max-payload64)
                       (bvugt (bvadd allocated64 trailer64) remaining64))))
           :named unsafe-allocation-def))
(assert (! (= violation
              (or (and accept
                       (or overflow
                           (not length-in-range)
                           (not frame-complete)))
                  unsafe-allocation))
           :named violation-def))
(assert (! violation :named query))
(check-sat)
(get-unsat-core)
