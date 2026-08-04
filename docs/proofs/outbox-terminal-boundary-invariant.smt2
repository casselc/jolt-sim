(set-option :produce-unsat-cores true)
(declare-datatypes ((Kind 0)) (((deadline) (cancel))))
(declare-datatypes ((Boundary 0)) (((post_commit) (pre_ack) (pre_mark))))
(declare-const kind Kind)
(declare-const boundary Boundary)
(declare-const offset Int)
(declare-const expired Bool)
(declare-const committed Bool)
(declare-const pre_ack_reply_wins Bool)
(declare-const ack_validated Bool)
(declare-const mark_count Int)
(declare-const pending Bool)
(declare-const delivered Bool)
(declare-const violation Bool)

(assert (!
  (and
    (or (= offset (- 1)) (= offset 0) (= offset 1))
    (=> (= kind cancel) (and (= boundary pre_ack) (= offset 0))))
  :named valid_terminal_action))

(assert (! (= expired (and (= kind deadline) (>= offset 0)))
  :named expiration_semantics))
(assert (! (= committed true) :named commit_survives))
(assert (!
  (= ack_validated
     (and (= kind deadline)
          (or (= offset (- 1))
              (and expired (= boundary pre_mark))
              (and expired (= boundary pre_ack) pre_ack_reply_wins))))
  :named acknowledgement_semantics))
(assert (!
  (= mark_count
     (ite (and (= kind deadline) (= offset (- 1))) 1 0))
  :named marking_semantics))
(assert (! (= delivered (= mark_count 1)) :named delivered_projection))
(assert (! (= pending (= mark_count 0)) :named pending_projection))

(assert (!
  (= violation
     (not
      (and
        committed
        (<= 0 mark_count)
        (<= mark_count 1)
        (or pending delivered)
        (not (and pending delivered))
        (= delivered (= mark_count 1))
        (= pending (= mark_count 0))
        (=> (> mark_count 0) ack_validated)
        (=> expired (= mark_count 0))
        (=> (= kind cancel)
            (and (not ack_validated) (= mark_count 0) pending))
        (=> (and (= kind deadline) (= offset (- 1)))
            (and ack_validated (= mark_count 1) delivered))
        (=> (and expired (= boundary pre_mark))
            (and ack_validated (= mark_count 0) pending))
        (=> (and expired (= boundary post_commit))
            (and (not ack_validated) (= mark_count 0) pending)))))
  :named invariant_definition))
(assert (! violation :named violation_query))
