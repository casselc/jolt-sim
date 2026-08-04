# Virtual-clock alarm invariants

This proof constrains the alarm registry that wakes modeled blocking resources
when virtual monotonic time advances. It is deliberately smaller than a task
scheduler: time advancement remains an explicit scheduler/scenario decision.

## Bounded claim

For one alarm with exact integer deadline and a trace of at most four
`register`, `advance-to`, `cancel`, or no-op transitions:

1. an alarm never publishes before its deadline;
2. it publishes at most once;
3. registration at `deadline <= now` publishes immediately and retains no
   pending alarm;
4. advancement retains no pending alarm whose deadline is now due; and
5. cancellation prevents later publication unless advancement had already
   removed and committed the alarm for publication.

The finite model bounds time and deadline to `0..3`, one alarm lifecycle, and
four transitions. Advancement targets are monotone. It omits multiple alarms,
promise implementation semantics, callback failures (the implementation uses
only core promises), arbitrary state corruption, task scheduling policy, and
the Jolt host ABI. Those are covered separately by executable tests and source
contracts.

## Live source facts

At the jolt-sim base `c827d8bfbafeb938a178b9fcfe9ae639a1fdc5c0`:

- `src/jolt/sim/net/posix_loopback.clj` registers a readiness promise while
  holding the POSIX world lock, but finite `poll` parks with a host-timed
  `deref`. A frozen virtual clock therefore cannot wake the parked call merely
  by advancing.
- The pinned simulation compiler is casselc/jolt
  `9fc64f93eba8b56a319f91bb1a322e2efced9c70`. Its
  `host/chez/sim/runtime.ss` redirects both `System/nanoTime` and
  `jolt.host/mono-nanos` through the same exact `:mono-nanos` clock descriptor
  and validates nondecreasing exact-integer results. A second clock ABI is not
  needed.
- POSIX readiness publication clears registered waiters before delivering
  their promises under the world lock. The alarm integration therefore shares
  that same promise: readiness and deadline publication may race, but core
  promise delivery is idempotent and `poll` always recomputes readiness/time.

## Negated query and controls

[`virtual-clock-alarm-invariant.smt2`](virtual-clock-alarm-invariant.smt2)
asserts a single `violation` flag equivalent to any early delivery, duplicate
delivery, due-but-pending state, or cancelled-and-delivered state across all
four transitions. On 2026-08-04, the exact checked-in model passed
`chiasmus_lint`; `chiasmus_verify` returned `unsat` with the named core:

```text
initial_domain transition_0 transition_1 transition_2 transition_3
violation_definition violation_query
```

This means no counterexample exists within the recorded domain and bound. It
does not prove unmodeled concurrency or host behavior.

The same violation predicates over
[`virtual-clock-alarm-lost-wake-control.smt2`](virtual-clock-alarm-lost-wake-control.smt2)
returned `sat` with the intended buggy witness:

```clojure
{:deadline 0
 :time-before 0
 :operation :register
 :status-after :pending
 :deliveries-after 0
 :violation true}
```

The corrected model was also queried without `violation_query`, constraining
`deadline = time0`, `op0 = register`, `status1 = delivered`, and
`deliveries1 = 1`. It returned `sat` at deadline/time `1`, proving the inclusive
boundary and a valid exactly-once outcome remain reachable.

## Implementation mapping

`src/jolt/sim/clock.clj` maps the proof transitions directly:

- registration, cancellation, and advancement mutate one state atom under one
  clock lock;
- already-due registration never enters `:alarms` and delivers before return;
- advancement selects and removes every due alarm under the lock, then delivers
  its private promise outside the lock in `[deadline,id]` order;
- cancellation only removes a still-pending id, so it is idempotent and cannot
  revoke delivery already committed by advancement; and
- snapshots omit promises and host identities.

`src/jolt/sim/net/posix_loopback.clj` retains its real-time default. When given
the same virtual clock used by `jolt.sim.runtime`, finite `poll` registers an
alarm and readiness waiter on one shared promise, parks without a host timeout,
and cancels the alarm during waiter cleanup. Registering the alarm while holding
the POSIX world lock closes the readiness gap; the clock's already-due branch
closes the advance-before-register gap. The clock never auto-advances because a
runnable peer may publish readiness before the deadline.

Executable companion tests cover the SAT witness, exact inclusive boundary,
multiple same-deadline alarms, early non-delivery, idempotent cancellation,
backward-time rejection without mutation, virtual-time `poll` timeout, and
readiness winning before a virtual deadline with alarm cleanup.
