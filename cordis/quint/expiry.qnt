// -*- mode: Bluespec; -*-
/**
 * expiry: what the operation API's deadlines do to the rules operation.qnt
 * takes as given (docs/cordis/rfc/0007-boundaries.md, "Time").
 *
 * operation.qnt remembers a tombstone for the incarnation's life. The API
 * cannot: a submit carries a deadline, `not_after`, the executor refuses
 * one that arrives after it, and so remembers an id only until then; and it
 * releases a result nobody acknowledged after a backstop. This model adds
 * time. The executor decides everything by its own clock; the platform's
 * only part is naming the deadline, so skew changes how long the window is
 * and not whether the rules hold; a clock that goes back is what breaks
 * them.
 *
 * The executor answers a query about the attempt:
 *  - held, finished: the platform captures it;
 *  - tombstoned: the result was released (the backstop): forgotten,
 *    settled at the worst case R;
 *  - not held and inside the window (its clock <= not_after): never
 *    accepted, and abandoned at no cost through a cancel that tombstones
 *    the id, so a copy still in flight is refused;
 *  - not held and past the window: **expired**, not never-accepted. The
 *    executor has pruned what it knew of the id, so it cannot tell a
 *    submit it never saw from one it accepted, ran and released; the
 *    platform settles it at R.
 *
 * Properties:
 *  - runsOnce: an attempt runs at most once;
 *  - accounted: real spend never exceeds settled plus reserved spend;
 *  - neverAcceptedTrue: where the executor would say never accepted,
 *    the attempt has not run.
 * Mutants:
 *  - ABSENT_ALWAYS (`absentAlways`): an id with no record is never
 *    accepted whenever it is asked about, window or not (a tombstone
 *    pruned at not_after while the backstop that forgets a result is
 *    much later) -> accounted, neverAcceptedTrue;
 *  - PRUNE_EARLY (`pruneEarly`): a tombstone is dropped before its
 *    deadline -> runsOnce (a duplicate is accepted again);
 *  - LATE_ACCEPT (`lateAccept`): the executor ignores not_after on
 *    submit, with pruning at the deadline -> runsOnce;
 *  - CLOCK_BACK (`clockBack`): the executor's clock goes back after a
 *    tombstone was pruned -> runsOnce.
 * Witnesses (checked as not(w), expected VIOLATED): done, expiredW (a
 * query past the window settled at R), forgottenW (a released result),
 * abandonedW, lateRefused (a submit refused for arriving past not_after).
 */
module expiry {
  const ABSENT_ALWAYS: bool
  const PRUNE_EARLY: bool
  const LATE_ACCEPT: bool
  const CLOCK_BACK: bool

  type St = Sent | Settled | Abandoned | Lost
  type Ex = Absent | Held | Tomb

  pure val R = 2          // the attempt's worst-case cost: its reservation
  pure val W = 2          // the submit's window, by the platform's clock
  pure val SK = 1         // the skew between the platform's clock and the executor's
  pure val HOLD = 2       // the backstop: a finished, unacknowledged result is released after it
  pure val TMAX = 9
  pure val MAXM = 3       // submits in flight

  type S = {
    t: int,                 // ghost: real time
    back: int,              // how far the executor's clock has been set back
    notAfter: int,          // the submit's deadline, by the executor's clock
    st: St,
    msgs: int,
    ex: Ex, ran: bool, cost: int, ranAt: int,   // the executor's record: ran, cost and ranAt belong to a held attempt
    runs: int,                                  // ghost: times the attempt ran
    reserved: int, settled: int, real: int,
    backJumps: int, lateRefused: int, expired: int, forgotten: int, abandoned: int,
  }

  var s: S

  // the executor's clock
  def hc: int = s.t - s.back

  // the platform names a deadline W ahead by its own clock: by the executor's it is W +- SK
  action init = {
    nondet skew = 0.to(2 * SK).oneOf()
    s' = { t: 0, back: 0, notAfter: W + skew - SK, st: Sent, msgs: 1, ex: Absent, ran: false, cost: 0, ranAt: 0, runs: 0,
           reserved: R, settled: 0, real: 0, backJumps: 0, lateRefused: 0, expired: 0, forgotten: 0, abandoned: 0 }
  }

  action tick: bool = all { s.t < TMAX, s' = { ...s, t: s.t + 1 } }

  // the executor's clock goes back
  action clockBack: bool = all { CLOCK_BACK, s.backJumps < 1, s' = { ...s, back: s.back + 3, backJumps: 1 } }

  // the network duplicates and loses the submit
  action dup: bool = all { s.msgs > 0, s.msgs < MAXM, s' = { ...s, msgs: s.msgs + 1 } }
  action lose: bool = all { s.msgs > 0, s' = { ...s, msgs: s.msgs - 1 } }

  // a submit arrives: refused past its deadline; a copy of an attempt it holds or remembers changes nothing
  action deliver: bool = {
    val late = hc > s.notAfter and not(LATE_ACCEPT)
    all {
      s.msgs > 0,
      s' = { ...s, msgs: s.msgs - 1,
             ex: if (not(late) and s.ex == Absent) Held else s.ex,
             lateRefused: if (late) s.lateRefused + 1 else s.lateRefused },
    }
  }

  // the executor finishes a held attempt, spending 1..R
  action haloRuns: bool = {
    nondet c = 1.to(R).oneOf()
    all {
      s.ex == Held, not(s.ran),
      s' = { ...s, ran: true, cost: c, ranAt: hc, runs: s.runs + 1, real: s.real + c },
    }
  }

  // the backstop: a finished result nobody acknowledged is released, the id remembered
  action holdExpires: bool = all {
    s.ex == Held, s.ran, hc > s.ranAt + HOLD,
    s' = { ...s, ex: Tomb, ran: false },
  }

  // a tombstone is dropped once the deadline has passed (PRUNE_EARLY: whenever)
  action prune: bool = all {
    s.ex == Tomb, hc > s.notAfter or PRUNE_EARLY,
    s' = { ...s, ex: Absent },
  }

  // the platform captures a finished result, settled at its cost, and acknowledges it (the id tombstoned)
  action capture: bool = all {
    s.st == Sent, s.ex == Held, s.ran,
    s' = { ...s, st: Settled, ex: Tomb, ran: false, reserved: 0, settled: s.settled + s.cost },
  }

  // forgotten: the result was released. Expired: no record, past the window. Both settled at the worst case
  action unknown: bool = all {
    s.st == Sent,
    s.ex == Tomb or (s.ex == Absent and hc > s.notAfter and not(ABSENT_ALWAYS)),
    s' = { ...s, st: Lost, reserved: 0, settled: s.settled + R,
           forgotten: if (s.ex == Tomb) s.forgotten + 1 else s.forgotten,
           expired: if (s.ex == Absent) s.expired + 1 else s.expired },
  }

  // never accepted: no record, inside the window (ABSENT_ALWAYS: whenever). Abandoned at no cost through a cancel
  // that tombstones the id, so a copy still in flight is refused
  action abandon: bool = all {
    s.st == Sent, s.ex == Absent, hc <= s.notAfter or ABSENT_ALWAYS,
    s' = { ...s, st: Abandoned, ex: Tomb, reserved: 0, abandoned: s.abandoned + 1 },
  }

  action idle: bool = s' = s

  action step = any { tick, clockBack, dup, lose, deliver, haloRuns, holdExpires, prune, capture, unknown, abandon, idle }

  // ---- properties -------------------------------------------------------------------------
  val runsOnce: bool = s.runs <= 1
  val accounted: bool = s.real <= s.settled + s.reserved
  val neverAcceptedTrue: bool =
    (s.st == Sent and s.ex == Absent and (hc <= s.notAfter or ABSENT_ALWAYS)) implies s.runs == 0
  val safety: bool = runsOnce and accounted and neverAcceptedTrue

  // witnesses
  val done: bool = s.st == Settled
  val expiredW: bool = s.expired > 0
  val forgottenW: bool = s.forgotten > 0
  val abandonedW: bool = s.abandoned > 0
  val lateRefusedW: bool = s.lateRefused > 0
}
