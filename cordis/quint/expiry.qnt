// -*- mode: Bluespec; -*-
/**
 * expiry: what the operation API's deadlines do to the rules operation.qnt
 * takes as given (docs/cordis/rfc/0007-boundaries.md, "Time").
 *
 * operation.qnt remembers a tombstone for the incarnation's life. The API
 * cannot: a submit carries a deadline, `not_after`, the executor refuses
 * one that arrives after it, and so remembers an id only until then; and it
 * stops holding a finished result's body after a capture window. This
 * model adds time. The executor decides everything by its own monotonic
 * clock; the platform's only part is naming the deadline, so skew changes
 * how long the window is and not whether the rules hold; a clock that goes
 * back is what breaks them.
 *
 * What the executor keeps decides what its answers mean (halo-flash
 * revision 2, e2d5030):
 *  - a finished, unacknowledged attempt: its body until the window ends,
 *    then its **outcome** (status and usage) until it is acknowledged. The
 *    platform settles an outcome at its actual usage;
 *  - an acknowledged or cancelled id: a tombstone, until the deadline has
 *    passed;
 *  - anything else has no record, so it was never accepted. Inside the
 *    window that is `never_accepted`: a delayed copy may still arrive, so
 *    the attempt is closed only by a cancel that tombstones the id. Past
 *    the window it is `closed`: no copy can be accepted any more, and the
 *    attempt is settled at nothing with no further message.
 * This is sound only because an accepted attempt always leaves a record
 * until the platform acknowledges it; that is what DROP_OUTCOME removes.
 *
 * Properties:
 *  - runsOnce: an attempt runs at most once;
 *  - accounted: real spend never exceeds settled plus reserved spend;
 *  - neverAcceptedTrue: an attempt of the platform's with no record has
 *    not run.
 * Mutants:
 *  - DROP_OUTCOME (`dropOutcome`): the outcome of an unacknowledged,
 *    expired result is pruned with the tombstone, so "it ran" reads as
 *    `closed` -> accounted, neverAcceptedTrue;
 *  - CLOSED_EARLY (`closedEarly`): an attempt with no record is closed
 *    without waiting for the deadline or cancelling (a refusal read as the
 *    end of the attempt) -> accounted;
 *  - GUESS_DEADLINE (`guessDeadline`): a status query carries no
 *    deadline, so for an attempt whose submit never arrived the executor
 *    has none to compare its clock with, and guesses the earliest the
 *    platform could have named -> accounted (it answers `closed` while a
 *    copy with a later deadline can still be accepted). The verbs carry
 *    the immutable `not_after`, as submit does;
 *  - PRUNE_EARLY (`pruneEarly`): a tombstone is dropped before its
 *    deadline -> runsOnce (a duplicate is accepted again);
 *  - LATE_ACCEPT (`lateAccept`): the executor ignores not_after on
 *    submit, with pruning at the deadline -> runsOnce;
 *  - CLOCK_BACK (`clockBack`): the executor's clock goes back after a
 *    tombstone was pruned -> runsOnce.
 * Witnesses (checked as not(w), expected VIOLATED): done, outcomeSettledW
 * (an expired result settled at its usage), abandonedW (through a cancel),
 * closedW (past the window, with no message), lateRefusedW.
 */
module expiry {
  const DROP_OUTCOME: bool
  const CLOSED_EARLY: bool
  const GUESS_DEADLINE: bool
  const PRUNE_EARLY: bool
  const LATE_ACCEPT: bool
  const CLOCK_BACK: bool

  type St = Sent | Settled | Abandoned | Lost
  type Ex = Absent | Held | Outcome | Tomb

  pure val R = 2          // the attempt's worst-case cost: its reservation
  pure val W = 2          // the submit's window, by the platform's clock
  pure val SK = 1         // the skew between the platform's clock and the executor's
  pure val HOLD = 2       // the capture window: a finished, unacknowledged body is dropped after it
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
    backJumps: int, lateRefused: int, outcomeSettled: int, closed: int, abandoned: int,
  }

  var s: S

  // the executor's clock
  def hc: int = s.t - s.back

  // the platform names a deadline W ahead by its own clock: by the executor's it is W +- SK
  action init = {
    nondet skew = 0.to(2 * SK).oneOf()
    s' = { t: 0, back: 0, notAfter: W + skew - SK, st: Sent, msgs: 1, ex: Absent, ran: false, cost: 0, ranAt: 0, runs: 0,
           reserved: R, settled: 0, real: 0, backJumps: 0, lateRefused: 0, outcomeSettled: 0, closed: 0, abandoned: 0 }
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

  // the capture window ends: the body of a finished result nobody acknowledged goes, its outcome stays until
  // it is acknowledged (DROP_OUTCOME: it is dropped to a tombstone, to be pruned)
  action holdExpires: bool = all {
    s.ex == Held, s.ran, hc > s.ranAt + HOLD,
    s' = { ...s, ex: if (DROP_OUTCOME) Tomb else Outcome, ran: if (DROP_OUTCOME) false else s.ran },
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

  // an expired result: the platform settles its actual usage, with no receipt, and acknowledges the outcome
  action settleOutcome: bool = all {
    s.st == Sent, s.ex == Outcome,
    s' = { ...s, st: Settled, ex: Tomb, ran: false, reserved: 0, settled: s.settled + s.cost,
           outcomeSettled: s.outcomeSettled + 1 },
  }

  // never accepted, inside the window: closed through a cancel that tombstones the id, so a copy still in flight
  // is refused. Settled at nothing
  action abandon: bool = all {
    s.st == Sent, s.ex == Absent, hc <= s.notAfter,
    s' = { ...s, st: Abandoned, ex: Tomb, reserved: 0, abandoned: s.abandoned + 1 },
  }

  // closed: past the window with no record, so no copy can be accepted any more. Settled at nothing, no message
  // (CLOSED_EARLY: without waiting for the window, and without a cancel)
  action closed: bool = all {
    s.st == Sent, s.ex == Absent,
    // the deadline the executor compares with: the one the verb carries (GUESS_DEADLINE: the earliest it could be)
    hc > (if (GUESS_DEADLINE) W - SK else s.notAfter) or CLOSED_EARLY,
    s' = { ...s, st: Abandoned, reserved: 0, closed: s.closed + 1 },
  }

  action idle: bool = s' = s

  action step = any { tick, clockBack, dup, lose, deliver, haloRuns, holdExpires, prune, capture, settleOutcome, abandon, closed, idle }

  // ---- properties -------------------------------------------------------------------------
  val runsOnce: bool = s.runs <= 1
  val accounted: bool = s.real <= s.settled + s.reserved
  val neverAcceptedTrue: bool = (s.st == Sent and s.ex == Absent) implies s.runs == 0
  val safety: bool = runsOnce and accounted and neverAcceptedTrue

  // witnesses
  val done: bool = s.st == Settled
  val outcomeSettledW: bool = s.outcomeSettled > 0
  val closedW: bool = s.closed > 0
  val abandonedW: bool = s.abandoned > 0
  val lateRefusedW: bool = s.lateRefused > 0
}
