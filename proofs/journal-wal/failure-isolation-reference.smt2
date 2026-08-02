(set-option :produce-unsat-cores true)
(set-logic QF_LIA)

; Relational noninterference model: execution A has a healthy journal;
; execution B has arbitrary open and finalization failures. Both receive the
; same application result/exception token.
(declare-const app-kind Int)
(declare-const app-token Int)
(declare-const open-failure Int)
(declare-const finish-failure Int)
(declare-const open-caught Bool)
(declare-const finish-caught Bool)

(declare-const a-phase1 Int)
(declare-const a-phase2 Int)
(declare-const a-invoked Bool)
(declare-const a-app-kind Int)
(declare-const a-app-token Int)
(declare-const a-observed-kind Int)
(declare-const a-observed-token Int)

(declare-const b-phase1 Int)
(declare-const b-phase2 Int)
(declare-const b-invoked Bool)
(declare-const b-app-kind Int)
(declare-const b-app-token Int)
(declare-const b-observed-kind Int)
(declare-const b-observed-token Int)
(declare-const b-journal-open-healthy Bool)
(declare-const b-journal-healthy Bool)
(declare-const violation Bool)

(assert (! (and (<= 0 app-kind) (<= app-kind 1))
           :named app-kind-domain))
(assert (! (and (<= 0 open-failure) (<= open-failure 1))
           :named open-failure-domain))
(assert (! (and (<= 0 finish-failure) (<= finish-failure 1))
           :named finish-failure-domain))
; These are explicit wrapper-policy premises to be witnessed by executable
; fault injection, not conclusions hidden in the outcome equations.
(assert (! open-caught :named open-errors-are-caught))
(assert (! finish-caught :named finish-errors-are-caught))

; Healthy baseline: successful open reaches application; successful finish
; publishes the application's exact result or primary exception.
(assert (! (= a-phase1 1) :named a-open-transition))
(assert (! (= a-invoked (= a-phase1 1)) :named a-invocation))
(assert (! (= a-phase2 (ite a-invoked 2 (- 1))) :named a-app-transition))
(assert (! (= a-app-kind (ite a-invoked app-kind 2))
           :named a-app-kind-transition))
(assert (! (= a-app-token (ite a-invoked app-token (- 1)))
           :named a-app-token-transition))
(assert (! (= a-observed-kind a-app-kind) :named a-finish-kind-transition))
(assert (! (= a-observed-token a-app-token)
           :named a-finish-token-transition))

; Faulty journal: an uncaught open failure would stop before phase 1, and an
; uncaught finish failure would replace the application outcome. The policy
; premises above force the intended caught branches for every failure value.
(assert (! (= b-journal-open-healthy (= open-failure 0))
           :named b-open-health-transition))
(assert (! (= b-phase1
              (ite (and (not b-journal-open-healthy)
                         (not open-caught))
                   (- 1) 1))
           :named b-open-control-transition))
(assert (! (= b-invoked (= b-phase1 1)) :named b-invocation))
(assert (! (= b-phase2 (ite b-invoked 2 (- 1))) :named b-app-transition))
(assert (! (= b-app-kind (ite b-invoked app-kind 2))
           :named b-app-kind-transition))
(assert (! (= b-app-token (ite b-invoked app-token (- 1)))
           :named b-app-token-transition))
(assert (! (= b-journal-healthy
              (and b-journal-open-healthy (= finish-failure 0)))
           :named b-finish-health-transition))
(assert (! (= b-observed-kind
              (ite (and (= finish-failure 1) (not finish-caught))
                   2 b-app-kind))
           :named b-finish-kind-transition))
(assert (! (= b-observed-token
              (ite (and (= finish-failure 1) (not finish-caught))
                   finish-failure b-app-token))
           :named b-finish-token-transition))

(assert (! (= violation
              (or (not (= b-phase2 a-phase2))
                  (not (= b-invoked a-invoked))
                  (not (= b-observed-kind a-observed-kind))
                  (not (= b-observed-token a-observed-token))))
           :named noninterference-violation))
(assert (! violation :named query))
(check-sat)
(get-unsat-core)
