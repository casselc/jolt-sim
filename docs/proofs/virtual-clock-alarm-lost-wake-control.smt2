; Known-SAT control for the same one-alarm violation predicates. This buggy
; register transition retains an already-due alarm without delivering it.
(declare-datatypes () ((Status absent pending delivered cancelled)))
(declare-datatypes () ((Op noop register cancel advance)))
(declare-const deadline Int)
(declare-const time0 Int)
(declare-const status0 Status)
(declare-const deliveries0 Int)
(declare-const op0 Op)
(declare-const time1 Int)
(declare-const status1 Status)
(declare-const deliveries1 Int)

(assert (! (and (<= 0 deadline) (<= deadline 3)
                (<= 0 time0) (<= time0 3)
                (= status0 absent)
                (= deliveries0 0)
                (= op0 register))
           :named initial_domain))

; BUG: registration never checks deadline <= now and never publishes.
(assert (! (and (= time1 time0)
                (= status1 pending)
                (= deliveries1 deliveries0))
           :named buggy_register_transition))

(declare-const violation Bool)
(assert (! (= violation
  (or (> deliveries1 1)
      (and (= status1 delivered) (< time1 deadline))
      (and (= status1 pending) (<= deadline time1))
      (and (= status1 cancelled) (> deliveries1 0))))
  :named violation_definition))

(assert (! violation :named violation_query))
