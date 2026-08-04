; Known-SAT control for the same violation schema. BEGIN reports failure after
; physically entering a transaction; the injected legacy evaluator neither
; recovers nor poisons the connection.
(declare-datatypes () ((Probe ac_on ac_off probe_error no_probe)))
(declare-datatypes () ((BeginOutcome begin_success begin_failure begin_not_issued)))
(declare-datatypes () ((RollbackOutcome rollback_success rollback_failure rollback_not_issued)))

(declare-const pre Probe)
(declare-const begin_outcome BeginOutcome)
(declare-const post Probe)
(declare-const rollback_outcome RollbackOutcome)
(declare-const final_probe Probe)

(assert (! (not (= pre no_probe)) :named pre_probe_total))

(declare-const begin_issued Bool)
(assert (! (= begin_issued (= pre ac_on)) :named begin_issued_definition))
(assert (! (= (= begin_outcome begin_not_issued) (not begin_issued))
           :named begin_outcome_relevance))

(declare-const begin_failed Bool)
(assert (! (= begin_failed (= begin_outcome begin_failure))
           :named begin_failed_definition))
(assert (! (= (= post no_probe) (not begin_failed))
           :named post_probe_relevance))

(declare-const rollback_issued Bool)
(assert (! (= rollback_issued false) :named rollback_issued_definition))
(assert (! (= (= rollback_outcome rollback_not_issued)
              (not rollback_issued))
           :named rollback_outcome_relevance))
(assert (! (= (= final_probe no_probe)
              (not (= rollback_outcome rollback_success)))
           :named final_probe_relevance))

(declare-const failed_terminal Bool)
(assert (! (= failed_terminal (not (= begin_outcome begin_success)))
           :named failed_terminal_definition))

(declare-const autocommit_proven Bool)
(assert (! (= autocommit_proven
              (or (and begin_failed (= post ac_on))
                  (and begin_failed
                       (= post ac_off)
                       (= rollback_outcome rollback_success)
                       (= final_probe ac_on))))
           :named autocommit_proven_definition))

(declare-const incomplete_or_failed Bool)
(assert (! (= incomplete_or_failed
              (or (= pre probe_error)
                  (= pre ac_off)
                  (= post probe_error)
                  (= rollback_outcome rollback_failure)
                  (= final_probe probe_error)
                  (= final_probe ac_off)))
           :named incomplete_or_failed_definition))

(declare-const invalid_required_input Bool)
(assert (! (= invalid_required_input
              (and failed_terminal (not autocommit_proven)))
           :named invalid_required_input_definition))

(declare-const implementation_allow Bool)
(assert (! (= implementation_allow failed_terminal)
           :named evaluator_definition))

(assert (! (= pre ac_on) :named control_pre_clean))
(assert (! (= begin_outcome begin_failure)
           :named control_begin_reported_failure))
(assert (! (= post ac_off) :named control_physical_transaction_open))

(declare-const destructive_recovery Bool)
(assert (! (= destructive_recovery
              (and rollback_issued (not (= pre ac_on))))
           :named destructive_recovery_definition))

(declare-const violation Bool)
(assert (! (= violation
              (or destructive_recovery
                  (and (or incomplete_or_failed invalid_required_input)
                       implementation_allow)))
           :named violation_definition))

(assert (! violation :named violation_query))
