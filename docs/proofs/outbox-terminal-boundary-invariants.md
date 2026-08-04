# Outbox terminal-boundary invariants

This bounded proof defines the expected durable state when one committed
HTTP → SQLite → TCP outbox operation reaches a deadline or cancellation
boundary. It is a small oracle for the executable whole-application campaign,
not a proof of the HTTP, SQLite, TCP, scheduler, or host implementations.

## Bounded claim

The input is one terminal action:

- deadline at `post_commit`, `pre_ack`, or `pre_mark`, at offset `-1`, `0`, or
  `1` nanosecond relative to the one operation-wide absolute deadline; or
- cancellation at `pre_ack`.

Within that closed domain:

1. the command COMMIT survives every later terminal action;
2. marking occurs at most once and only after acknowledgement validation;
3. an operation expired before marking remains pending and unmarked;
4. exact-boundary expiry (`offset = 0`) has the same fail-closed semantics as
   overdue expiry;
5. a live operation (`offset = -1`) validates the acknowledgement and marks
   exactly once;
6. cancellation before acknowledgement remains pending and unmarked; and
7. the one row is exactly pending or delivered, with delivered equivalent to
   one successful mark.

At the pre-ack deadline action, advancing the virtual clock wakes the client
while the receiver is about to return its reply. Either side may win that
ordinary-thread race: the acknowledgement may or may not validate before the
client observes expiry. The model therefore leaves that intermediate fact
open while requiring the same durable result in both cases: zero marks and a
pending row. Post-COMMIT expiry cannot validate an acknowledgement; pre-mark
expiry occurs only after validation.

The model has one row, one operation, one terminal action, and no retry. It
omits arbitrary schedules, weak memory, native implementation behavior,
multiple outbox rows, duplicate acknowledgement, process failure, cleanup,
and the post-mark-COMMIT winner rule. Those require the executable scenario,
the existing crash-recovery lane, and separate lifecycle proofs.

## Solver results

On 2026-08-04, the exact checked-in
[`outbox-terminal-boundary-invariant.smt2`](outbox-terminal-boundary-invariant.smt2)
passed `chiasmus_lint`. `chiasmus_verify` returned `unsat` for the negated
invariant with this named core:

```text
expiration_semantics commit_survives acknowledgement_semantics
marking_semantics delivered_projection pending_projection
invariant_definition violation_query
```

This means no counterexample exists inside the stated domain and encoding. It
does not establish an unbounded theorem.

Two deliberately faulty controls both passed lint and returned `sat`:

- [`outbox-terminal-relative-timeout-control.smt2`](outbox-terminal-relative-timeout-control.smt2)
  resets a lower-level timeout at exact pre-ack expiry. Its witness has
  `expired = true`, `ack_validated = true`, `mark_count = 1`, and
  `delivered = true`.
- [`outbox-terminal-mark-before-check-control.smt2`](outbox-terminal-mark-before-check-control.smt2)
  marks before checking exact pre-mark expiry. It produces the same forbidden
  delivered state at `boundary = pre_mark`.

The corrected equations were also queried without the violation assertion.
Both required nonvacuity boundaries returned `sat`:

```clojure
{:boundary :pre-ack :offset -1 :expired false
 :ack-validated true :mark-count 1 :delivered true :pending false}

{:boundary :pre-mark :offset 0 :expired true
 :ack-validated true :mark-count 0 :delivered false :pending true}
```

Exact pre-ack expiry was also checked with both values of the reply-race
variable; both corrected states are pending with `mark-count = 0`.

## Source and executable mapping

The ordinary fixture creates one absolute deadline and carries it unchanged
through HTTP and TCP calls. Its optional operation-boundary observer runs
before the named clock sample; the simulator scenario may therefore advance
virtual time at a semantic boundary without replacing the application or its
libraries. Durable command evidence is published before the post-COMMIT clock
check, acknowledgement validation precedes the post-ack check, and the
pre-mark check precedes the SQLite marking transaction. The executable
campaign deliberately does not assert which ordinary thread wins after the
pre-ack clock advance; it asserts the invariant shared by both winners.

The executable Hegel campaign must map the model to the unchanged ordinary
application and additionally prove facts outside this SMT model:

- exact pending/delivered SQLite image and statement-plan position;
- bounded acknowledgement evidence;
- a virtual-clock snapshot with no retained alarms;
- no readiness waiters, native allocations, statements, database handles, or
  sockets after cleanup;
- handler-only routing; and
- a process-supervisor timeout remains distinct from an application virtual
  deadline.

Every failure must retain its Case/Outcome document and raw worker directory.
The generated input is the replay coordinate; Hegel should shrink offsets
toward exact boundary `0` and terminal plans toward the smallest failing case.
