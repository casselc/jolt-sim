// -*- mode: Bluespec; -*-
/**
 * operation: the lifecycle of an external operation across the platform's
 * ledger and an ephemeral executor (docs/cordis/rfc/0007-boundaries.md).
 *
 * The executor (halo-flash) is not durable: it holds an accepted operation
 * and its result for a while, a restart drops all of it, and under load it
 * may evict a finished result early. It can say "running", "done, this
 * much" or "I do not know that operation". The platform's ledger holds each
 * attempt under a reservation of its worst-case cost R until the executor
 * reports a result, which it captures and settles at its actual cost.
 *
 *  - an attempt the executor no longer knows is **unknown**, not
 *    never-accepted: it may have run (a restart after it finished, before
 *    its result was captured), and its tokens are spent. The ledger
 *    settles it at the worst case, R, and the operation may be attempted
 *    again only under a new attempt identity;
 *  - a retention **promise** (PROMISE) is that a finished result is held
 *    until the platform has captured it: the executor refuses admission
 *    before it evicts one. Without it ("ten minutes, unless capacity
 *    evicts sooner") eviction can make a captured-in-time result unknown,
 *    and the ledger pays the worst case for work that was cheaper.
 *
 * Properties:
 *  - appliedOnce: at most one receipt is applied per operation;
 *  - accounted: real spend never exceeds settled plus reserved spend;
 *  - reservationsHeld: the reserved amount is exactly R per pending attempt;
 *  - noOvercharge: settled spend never exceeds real spend (holds only
 *    where nothing is ever unknown: PROMISE and no restart).
 * Mutant:
 *  - RESUBMIT_SAME (`resubmitSame`, `resubmitSameEvict`): an unknown
 *    attempt is treated as never accepted and sent again under the same
 *    identity, settling nothing for what it may have run (the idempotency
 *    key alone, taken as an operation contract) -> accounted.
 * Worlds: RESTART (the executor may restart, dropping all it holds);
 * PROMISE as above (`promised`: no restart, a promise -> noOvercharge
 * holds; `cacheLike`: no promise -> noOvercharge is violated, accounted
 * holds).
 * Witnesses (checked as not(w), expected VIOLATED): done, restartedThenDone,
 * lostAfterRun (an attempt that finished, then was forgotten, charged
 * the worst case), lostBeforeRun, evicted.
 */
module operation {
  const RESTART: bool
  const PROMISE: bool
  const RESUBMIT_SAME: bool

  type St = Pending | Settled | Lost
  // held: the executor holds the attempt (accepted, and not dropped); ran: it finished, at cost
  type Attempt = { op: int, st: St, held: bool, ran: bool, cost: int }

  pure val N = 2          // operations, emitted in order
  pure val R = 2          // an attempt's worst-case cost: its reservation
  pure val MAXA = 3       // attempts per operation

  type S = {
    pc: int, tries: int,
    attempts: List[Attempt],
    reserved: int, settled: int,
    real: int,             // ghost: what the executor actually spent
    applied: List[int],
    restarts: int, evicted: int, lostAfterRun: int, lostBeforeRun: int,
  }

  var s: S

  action init = s' = {
    pc: 0, tries: 0, attempts: List(), reserved: 0, settled: 0, real: 0, applied: [0, 0],
    restarts: 0, evicted: 0, lostAfterRun: 0, lostBeforeRun: 0,
  }

  pure def pending(l: List[Attempt], op: int): bool = l.indices().exists(a => l[a].op == op and l[a].st == Pending)

  // submit an attempt under a reservation of R; the executor accepts it at once
  action send: bool = all {
    s.pc < N, s.applied[s.pc] == 0, s.tries < MAXA, not(pending(s.attempts, s.pc)),
    s' = { ...s,
      attempts: s.attempts.append({ op: s.pc, st: Pending, held: true, ran: false, cost: 0 }),
      tries: s.tries + 1, reserved: s.reserved + R },
  }

  // the executor finishes a held attempt, spending 1..R
  action worldRuns: bool = {
    nondet a = s.attempts.indices().oneOf()
    nondet c = 1.to(R).oneOf()
    val at = s.attempts[a]
    all {
      at.st == Pending, at.held, not(at.ran),
      s' = { ...s, attempts: s.attempts.replaceAt(a, { ...at, ran: true, cost: c }), real: s.real + c },
    }
  }

  // the platform captures a held, finished attempt's result: settled at its cost, the receipt applied
  action capture: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.st == Pending, at.held, at.ran,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, st: Settled }),
        reserved: s.reserved - R, settled: s.settled + at.cost,
        applied: s.applied.replaceAt(at.op, s.applied[at.op] + 1),
        pc: s.pc + 1, tries: 0 },
    }
  }

  // the executor restarts: it holds nothing afterwards; work in flight is lost, its spend (a ghost) is not
  action restart: bool = all {
    RESTART,
    s' = { ...s,
      attempts: s.attempts.indices().fold(s.attempts, (l, b) => l.replaceAt(b, { ...l[b], held: false })),
      restarts: s.restarts + 1 },
  }

  // the executor evicts a finished result. With a promise it does so only once the platform has captured it
  action evict: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.held, at.ran,
      PROMISE implies at.st != Pending,
      s' = { ...s, attempts: s.attempts.replaceAt(a, { ...at, held: false }), evicted: s.evicted + 1 },
    }
  }

  // the platform asks about a pending attempt and the executor does not know it. Unknown is not
  // never-accepted: the attempt is settled at its worst case, and the operation may be attempted again
  // under a new identity. RESUBMIT_SAME is the mutant that sends it again as it was, settling nothing
  action unknown: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.st == Pending, not(at.held),
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, if (RESUBMIT_SAME) { ...at, held: true, ran: false, cost: 0 } else { ...at, st: Lost }),
        reserved: if (RESUBMIT_SAME) s.reserved else s.reserved - R,
        settled: if (RESUBMIT_SAME) s.settled else s.settled + R,
        lostAfterRun: if (at.ran) s.lostAfterRun + 1 else s.lostAfterRun,
        lostBeforeRun: if (at.ran) s.lostBeforeRun else s.lostBeforeRun + 1 },
    }
  }

  action idle: bool = s' = s

  action step = any { send, worldRuns, capture, restart, evict, unknown, idle }

  // ---- properties -------------------------------------------------------------------------
  val appliedOnce: bool = s.applied.foldl(true, (ok, n) => ok and n <= 1)
  val accounted: bool = s.real <= s.settled + s.reserved
  val reservationsHeld: bool = s.reserved == R * s.attempts.indices().filter(a => s.attempts[a].st == Pending).size()
  val noOvercharge: bool = s.settled <= s.real
  val safety: bool = appliedOnce and accounted and reservationsHeld

  // witnesses
  val done: bool = s.pc == N
  val restartedThenDone: bool = s.restarts > 0 and s.pc == N
  val lostAfterRunW: bool = s.lostAfterRun > 0
  val lostBeforeRunW: bool = s.lostBeforeRun > 0
  val evictedW: bool = s.evicted > 0
}
