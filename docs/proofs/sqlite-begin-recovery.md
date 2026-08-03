# SQLite begin-recovery invariant

This proof constrains the first transaction-aware extension of
`jolt.sim.sqlite`. It does not claim that the current scripted SQLite world
already implements transactions.

## Bounded claim

For one SQLite outer `BEGIN` attempt at logical transaction depth zero:

1. a failed attempt may leave the connection reusable only after a concrete
   `sqlite3_get_autocommit != 0` observation proves that no transaction is
   active; and
2. recovery may issue `ROLLBACK` only when the pre-probe proved autocommit and
   the failed `BEGIN` was subsequently observed to have opened a transaction.

The finite domain is the eight R0-R7 outcomes documented by `db` commit
`041b400484b6e951f4e226280d330acc57e6ebed`: pre/post/final probes are
`autocommit`, `in-transaction`, or `error`; `BEGIN` succeeds or fails; and the
counter-rollback succeeds or fails. The model covers one direct recovery
attempt only. It omits nested savepoints, transaction bodies, commit failure,
concurrent connection use, SQLite locking, and durability.

## Live source facts

At `db` commit `041b400`:

- `db.sqlite/get-autocommit` is a nonblocking wrapper over
  `sqlite3_get_autocommit`.
- `jdbc.core/verified-sqlite-begin!` probes before `BEGIN`, refuses to issue
  `BEGIN` or `ROLLBACK` when the pre-probe is uncertain or already active,
  probes after a reported begin failure, and only counter-rolls back when that
  post-probe observes an active transaction.
- A successful counter-rollback is not trusted by itself: a final probe must
  observe autocommit before the connection is reusable. Every other branch
  poisons the connection while retaining the original begin exception as the
  primary throwable when one exists.

At jolt-sim base `ac8e0d884b0c57315a9fbcb7d60c5d88ebb6d890`,
`src/jolt/sim/sqlite.clj` has an ordered statement-plan oracle with connection
error/change/row-id state. Its 22-handler registry has no
`sqlite3_get_autocommit`, its connection state has no transaction boundary,
and successful statement plans return predeclared rows. These are the exact
gaps this feature slice must close narrowly.

## Negated query and results

[`sqlite-begin-recovery-invariant.smt2`](sqlite-begin-recovery-invariant.smt2)
asks whether either of these exists:

- a failed/incomplete attempt which the implementation marks reusable without
  an autocommit proof; or
- a recovery rollback issued when the pre-probe did not prove autocommit.

On 2026-08-02, the exact model passed `chiasmus_lint` and
`chiasmus_verify` returned `unsat`. Its named core includes the begin/post/
rollback/final relevance definitions, the autocommit proof, evaluator,
destructive-recovery, violation definition, and violation query. This means no
counterexample exists in the recorded one-attempt finite model; it is not a
proof of unmodeled SQLite behavior.

The same query over
[`sqlite-begin-recovery-fail-open-control.smt2`](sqlite-begin-recovery-fail-open-control.smt2)
returned `sat` with the expected witness:

```clojure
{:pre :autocommit
 :begin :reported-failure
 :post :in-transaction
 :rollback :not-issued
 :autocommit-proven? false
 :implementation-reusable? true
 :violation? true}
```

Three non-vacuity/boundary checks used the corrected model with the final
violation assertion removed and each row constrained in turn:

| Control | Required outcome | Solver |
|---|---|---|
| R1 | failed begin, post-probe autocommit, reusable, no rollback | SAT |
| R2 | failed begin, post-probe active, rollback success, final autocommit, reusable | SAT |
| R6 | pre-existing transaction, no begin, no rollback, not reusable | SAT |

## Implementation obligations

The simulator extension must stay smaller than a SQL engine:

- add the exact `sqlite3_get_autocommit [:pointer] :int` handler and
  per-connection autocommit/transaction staging state;
- recognize only the transaction-control statements driven by ordinary
  `jdbc.core` (`BEGIN`, `COMMIT`, and `ROLLBACK`) in addition to exact existing
  statement plans;
- let a declared fault distinguish the physical transition from the reported
  `sqlite3_step` result, so the SAT witness is executable;
- derive committed versus rolled-back row visibility from minimal staged
  state rather than predeclaring both results; and
- expose enough summary/effect evidence to assert R0-R7 routing, poison/cleanup,
  and no live handles without leaking host exception objects into replay data.

The ordinary HTTP fixture must continue importing only public `jdbc.core`,
`jolt.http.server`, and `teensyp.client`. The simulator owns the boundary
model and scenario configuration; application and library code must not gain
simulation-specific branches.
