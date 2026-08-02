(set-option :produce-unsat-cores true)
(set-logic QF_LIA)

; General one-step ranking proof for an arbitrary finite retry allowance.
; Status encoding: 0 = active, 2 = failed.  The modeled outcome is EINTR.
(declare-const max-eintr-retries Int)
(declare-const total Int)

(declare-const status0 Int)
(declare-const status1 Int)
(declare-const status2 Int)

(declare-const position0 Int)
(declare-const position1 Int)
(declare-const position2 Int)

(declare-const accepted0 Int)
(declare-const accepted1 Int)
(declare-const accepted2 Int)

(declare-const retries0 Int)
(declare-const retries1 Int)
(declare-const retries2 Int)

(declare-const rank0 Int)
(declare-const rank1 Int)
(declare-const initial-rank Int)

(declare-const safety Bool)
(declare-const violation Bool)

; Every active state satisfying this invariant is covered, not just the first
; attempt.  Therefore the one-step result can be applied inductively.
(assert (! (<= 0 max-eintr-retries) :named finite-retry-domain))
(assert (! (<= 1 total) :named total-domain))
(assert (! (= status0 0) :named active-prestate))
(assert (! (and (<= 0 position0) (< position0 total))
           :named incomplete-position-domain))
(assert (! (<= 0 accepted0) :named accepted-domain))
(assert (! (and (<= 0 retries0)
                (<= retries0 max-eintr-retries))
           :named active-retry-invariant))
(assert (! (= rank0
              (- (+ max-eintr-retries 1) retries0))
           :named rank0-definition))
(assert (! (= initial-rank (+ max-eintr-retries 1))
           :named initial-rank-definition))

; One EINTR preserves byte/publication positions and consumes one unit of rank.
(assert (! (= position1 position0) :named eintr-position-transition))
(assert (! (= accepted1 accepted0) :named eintr-accepted-transition))
(assert (! (= retries1 (+ retries0 1)) :named eintr-retry-transition))
(assert (! (= rank1
              (- (+ max-eintr-retries 1) retries1))
           :named rank1-definition))
(assert (! (= status1
              (ite (> retries1 max-eintr-retries) 2 0))
           :named eintr-status-transition))

; Observe one stuttering step after the EINTR.  Once failure is reached, no
; later attempt or state/publication change is permitted.
(assert (! (= status2 status1) :named absorbing-status-transition))
(assert (! (= position2 position1) :named absorbing-position-transition))
(assert (! (= accepted2 accepted1) :named absorbing-accepted-transition))
(assert (! (= retries2 retries1) :named absorbing-retry-transition))

; The SMT query negates the local inductive/ranking obligations.  Since rank is
; a positive natural in every active state and decreases by exactly one on each
; active EINTR, well-foundedness excludes an infinite all-EINTR active trace.
; Rank one is the boundary: that EINTR reaches absorbing failure at rank zero.
(assert (! (= safety
              (and
                (> initial-rank 0)
                (> rank0 0)
                (= rank1 (- rank0 1))
                (= position1 position0)
                (= accepted1 accepted0)
                (or (= status1 0) (= status1 2))
                (=> (= status1 0)
                    (and (> rank1 0)
                         (< rank1 rank0)
                         (<= retries1 max-eintr-retries)))
                (=> (= rank0 1)
                    (and (= status1 2)
                         (= rank1 0)
                         (= retries1 (+ max-eintr-retries 1))))
                (=> (= status1 2)
                    (and (= rank0 1)
                         (= rank1 0)
                         (= retries1 (+ max-eintr-retries 1))
                         (= status2 2)
                         (= position2 position1)
                         (= accepted2 accepted1)
                         (= retries2 retries1)))))
           :named safety-definition))
(assert (! (= violation (not safety)) :named violation-definition))
(assert (! violation :named query))
(check-sat)
(get-unsat-core)
