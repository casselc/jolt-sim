// -*- mode: Bluespec; -*-
/**
 * operation: the lifecycle of an external operation across the platform's
 * ledger and an ephemeral executor (docs/cordis/rfc/0007-boundaries.md).
 *
 * The platform submits an attempt of an operation as a message that the
 * network may lose, delay and duplicate; the executor (halo-flash) holds
 * what it accepts until the platform acks it, a restart drops everything it
 * holds, and (without a promise) it may evict a finished result. It names
 * the incarnation it is, and answers a query about an attempt:
 *
 *  - done / running: it holds the attempt;
 *  - **never accepted**: this incarnation has no record of it and has not
 *    forgotten one (no tombstone, no eviction marker);
 *  - **forgotten / unknown**: it may have run, and its record is gone
 *    (another incarnation answers, or a finished result was evicted).
 *
 * The platform holds each attempt under a reservation of its worst-case
 * cost R. Done: settled at the actual cost, acked (the executor tombstones
 * the id). Unknown or forgotten: settled at R, and the operation may go on
 * only under a new attempt. Never accepted: **safe to act on, at no cost**,
 * given three rules, each with a mutant that breaks it:
 *
 *  1. **incarnation fencing** on submit: a submit carries the incarnation
 *     the platform last learned, and a stale one is refused, so a message
 *     delayed past a restart cannot start an attempt the platform has
 *     already accounted for (NO_FENCE);
 *  2. **tombstones**: a cancelled or acked attempt id is refused for as
 *     long as a message for it can still arrive (the submit's not_after
 *     bounds that), so a duplicate or late submit does not run it again
 *     (NO_TOMB drops the tombstone at once; CACHE_TOMB evicts it under
 *     load while a message is in flight);
 *  3. **abandon only through a cancel that tombstones the id**: giving up
 *     on a never-accepted attempt without it lets the submit still in
 *     flight run uncounted (ABANDON_UNTOMBED).
 * Resending the same identity is the other safe act on never-accepted.
 * RESUBMIT_SAME is the prohibition: sending an unknown or forgotten
 * attempt again as it was, settling nothing for what it may have run.
 *
 * Properties:
 *  - runsOnce: an attempt runs at most once (any incarnation);
 *  - appliedOnce: at most one receipt is applied;
 *  - accounted: real spend never exceeds settled plus reserved spend;
 *  - reservationsHeld: reserved is exactly R per attempt still sent;
 *  - neverAcceptedTrue: an attempt the executor would call never accepted
 *    has not run;
 *  - noOvercharge: settled spend never exceeds real spend (holds only
 *    where nothing is unknown: a promise, no restart).
 * Worlds: RESTART (the executor may restart); DUP (the network duplicates
 * a submit); PROMISE (a finished result is held until acked; without it
 * the executor may evict one under load, leaving a forgotten marker).
 * REFUSE (`refuse`) lets the executor refuse a delivered copy: that closes the copy, not the attempt (RFC 0007 rule 2).
 * REFUSE_EARLY (`refuseEarly`) is the mutant that takes a refusal for the end of the attempt and releases its reservation
 * (RFC 0008 revision 4's table did, for `never_accepted` and every refusal) -> accounted.
 * Witnesses (checked as not(w), expected VIOLATED): done,
 * restartedThenDone, lostAfterRunW, abandonedW (a lost submit abandoned
 * at no cost), fencedW, duplicateW, evictedW.
 */
module operation {
  const RESTART: bool
  const PROMISE: bool
  const DUP: bool
  const NO_FENCE: bool
  const NO_TOMB: bool
  const ABANDON_UNTOMBED: bool
  const CACHE_TOMB: bool
  const RESUBMIT_SAME: bool
  const REFUSE: bool         // world: the executor may refuse a delivered copy (policy, capacity), closing that copy only
  const REFUSE_EARLY: bool   // mutant: the platform takes a refused copy as the end of the attempt and releases it

  type St = Sent | Settled | Cancelled | Lost
  type Ex = Absent | Held | Tomb | Forgot
  // tag: the incarnation the platform named in the submit
  type Attempt = { st: St, tag: int }
  type Msg = { a: int, tag: int }

  pure val R = 2          // an attempt's worst-case cost: its reservation
  pure val MAXA = 3       // attempts of the operation
  pure val MAXM = 3       // submits in flight

  type S = {
    attempts: List[Attempt],
    // the executor, per attempt id: what it holds, whether it finished, at what cost
    ex: List[Ex], exRan: List[bool], exCost: List[int],
    runs: List[int],       // ghost: times each attempt ran
    msgs: List[Msg],       // submits in flight
    inc: int,              // the executor's incarnation
    known: int,            // the incarnation the platform last learned
    reserved: int, settled: int,
    real: int,             // ghost: what the executor actually spent
    applied: int,
    refd: List[bool],      // per attempt: the platform saw a copy refused
    refusals: int, releasedOnRefusal: int,
    restarts: int, evicted: int, lostAfterRun: int, abandoned: int, fenced: int, duplicates: int,
  }

  var s: S

  action init = s' = {
    attempts: List(), ex: List(), exRan: List(), exCost: List(), runs: List(), msgs: List(),
    inc: 0, known: 0, reserved: 0, settled: 0, real: 0, applied: 0, refd: List(), refusals: 0, releasedOnRefusal: 0,
    restarts: 0, evicted: 0, lostAfterRun: 0, abandoned: 0, fenced: 0, duplicates: 0,
  }

  pure def removeAt(l: List[Msg], i: int): List[Msg] = l.slice(0, i).concat(l.slice(i + 1, l.length()))
  def inFlight(a: int): int = s.msgs.indices().filter(m => s.msgs[m].a == a).size()

  // ---- the platform submits ----------------------------------------------------------------------
  action send: bool = {
    val a = s.attempts.length()
    all {
      s.applied == 0, a < MAXA,
      not(s.attempts.indices().exists(b => s.attempts[b].st == Sent)),
      s' = { ...s,
        attempts: s.attempts.append({ st: Sent, tag: s.known }),
        refd: s.refd.append(false), ex: s.ex.append(Absent), exRan: s.exRan.append(false), exCost: s.exCost.append(0), runs: s.runs.append(0),
        msgs: s.msgs.append({ a: a, tag: s.known }), reserved: s.reserved + R },
    }
  }

  // ---- the network ------------------------------------------------------------------------------
  action dup: bool = {
    nondet m = s.msgs.indices().oneOf()
    all { DUP, s.msgs.length() < MAXM, s' = { ...s, msgs: s.msgs.append(s.msgs[m]) } }
  }

  action lose: bool = {
    nondet m = s.msgs.indices().oneOf()
    s' = { ...s, msgs: removeAt(s.msgs, m) }
  }

  // a submit arrives: refused if stale (fencing) or the id is tombstoned or forgotten; a duplicate of a held
  // attempt changes nothing; otherwise accepted
  action deliver: bool = {
    nondet m = s.msgs.indices().oneOf()
    val msg = s.msgs[m]
    val stale = not(NO_FENCE) and msg.tag != s.inc
    nondet refuse = Set(true, false).oneOf()
    val refused = REFUSE and refuse and not(stale) and s.ex[msg.a] == Absent
    val accept = not(stale) and not(refused) and s.ex[msg.a] == Absent
    s' = { ...s,
      refd: if (refused) s.refd.replaceAt(msg.a, true) else s.refd,
      refusals: if (refused) s.refusals + 1 else s.refusals,
      msgs: removeAt(s.msgs, m),
      ex: if (accept) s.ex.replaceAt(msg.a, Held) else s.ex,
      fenced: if (stale) s.fenced + 1 else s.fenced,
      duplicates: if (not(stale) and not(accept) and not(refused)) s.duplicates + 1 else s.duplicates }
  }

  // ---- the executor -----------------------------------------------------------------------------
  // finishes a held attempt, spending 1..R
  action worldRuns: bool = {
    nondet a = s.ex.indices().oneOf()
    nondet c = 1.to(R).oneOf()
    all {
      s.ex[a] == Held, not(s.exRan[a]),
      s' = { ...s, exRan: s.exRan.replaceAt(a, true), exCost: s.exCost.replaceAt(a, c),
             runs: s.runs.replaceAt(a, s.runs[a] + 1), real: s.real + c },
    }
  }

  // restarts: a new incarnation holds nothing (no records, no tombstones); work in flight is lost, its spend is not
  action restart: bool = all {
    RESTART,
    s' = { ...s,
      ex: s.ex.indices().fold(s.ex, (l, a) => l.replaceAt(a, Absent)),
      exRan: s.exRan.indices().fold(s.exRan, (l, a) => l.replaceAt(a, false)),
      inc: s.inc + 1, restarts: s.restarts + 1 },
  }

  // without a promise, evicts a finished, unacked result under load, leaving a forgotten marker
  action evict: bool = {
    nondet a = s.ex.indices().oneOf()
    all {
      not(PROMISE), s.ex[a] == Held, s.exRan[a],
      s' = { ...s, ex: s.ex.replaceAt(a, Forgot), exRan: s.exRan.replaceAt(a, false), evicted: s.evicted + 1 },
    }
  }

  // drops a tombstone: once no submit for the id can still arrive (the submit's not_after has passed); CACHE_TOMB
  // drops one under load whatever is in flight
  action evictTomb: bool = {
    nondet a = s.ex.indices().oneOf()
    all {
      s.ex[a] == Tomb, CACHE_TOMB or inFlight(a) == 0,
      s' = { ...s, ex: s.ex.replaceAt(a, Absent) },
    }
  }

  // ---- the platform learns and decides ----------------------------------------------------------
  action learn: bool = all { s.known != s.inc, s' = { ...s, known: s.inc } }

  // a held, finished attempt of the incarnation the platform submitted to: settled at its cost, the receipt
  // applied, acked (the id tombstoned)
  action capture: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.st == Sent, at.tag == s.inc, s.ex[a] == Held, s.exRan[a],
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, st: Settled }),
        ex: s.ex.replaceAt(a, if (NO_TOMB) Absent else Tomb), exRan: s.exRan.replaceAt(a, false),
        reserved: s.reserved - R, settled: s.settled + s.exCost[a], applied: s.applied + 1 },
    }
  }

  // unknown (another incarnation answers) or forgotten (a finished result was evicted): it may have run.
  // Settled at the worst case; the operation goes on under a new attempt. RESUBMIT_SAME sends it again as it was
  action unknown: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.st == Sent, at.tag != s.inc or s.ex[a] == Forgot,
      not(RESUBMIT_SAME) or s.msgs.length() < MAXM,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, if (RESUBMIT_SAME) { ...at, tag: s.inc } else { ...at, st: Lost }),
        msgs: if (RESUBMIT_SAME) s.msgs.append({ a: a, tag: s.inc }) else s.msgs,
        known: s.inc,
        reserved: if (RESUBMIT_SAME) s.reserved else s.reserved - R,
        settled: if (RESUBMIT_SAME) s.settled else s.settled + R,
        lostAfterRun: if (s.runs[a] > 0) s.lostAfterRun + 1 else s.lostAfterRun },
    }
  }

  // never accepted: this incarnation has no record and has forgotten nothing. The same identity is sent again...
  action resend: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.st == Sent, at.tag == s.inc, s.ex[a] == Absent, s.msgs.length() < MAXM,
      s' = { ...s, msgs: s.msgs.append({ a: a, tag: s.inc }) },
    }
  }

  // ...or abandoned through a cancel that tombstones the id, settled at nothing, the operation going on under a new
  // attempt. ABANDON_UNTOMBED gives up without the cancel; NO_TOMB's cancel leaves no tombstone
  action abandon: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.st == Sent, at.tag == s.inc, s.ex[a] == Absent,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, st: Cancelled }),
        ex: if (ABANDON_UNTOMBED or NO_TOMB) s.ex else s.ex.replaceAt(a, Tomb),
        reserved: s.reserved - R, abandoned: s.abandoned + 1 },
    }
  }

  // REFUSE_EARLY: a refused copy is taken for the end of the attempt: the reservation is released and the operation
  // goes on, with no cancel to tombstone the id. A delayed or duplicated copy may still be accepted, and run uncounted.
  // (The design waits for the tombstoning cancel, `abandon`, whatever the refusals said.)
  action refusedRelease: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      REFUSE_EARLY, at.st == Sent, at.tag == s.inc, s.refd[a],
      s' = { ...s, attempts: s.attempts.replaceAt(a, { ...at, st: Cancelled }), reserved: s.reserved - R,
             abandoned: s.abandoned + 1, releasedOnRefusal: s.releasedOnRefusal + 1 },
    }
  }

  action idle: bool = s' = s

  action step = any { send, dup, lose, deliver, worldRuns, restart, evict, evictTomb, learn, capture, unknown, resend, abandon, refusedRelease, idle }

  // ---- properties -------------------------------------------------------------------------
  val runsOnce: bool = s.runs.foldl(true, (ok, n) => ok and n <= 1)
  val appliedOnce: bool = s.applied <= 1
  val accounted: bool = s.real <= s.settled + s.reserved
  val reservationsHeld: bool = s.reserved == R * s.attempts.indices().filter(a => s.attempts[a].st == Sent).size()
  val neverAcceptedTrue: bool =
    s.attempts.indices().forall(a => (s.attempts[a].st == Sent and s.attempts[a].tag == s.inc and s.ex[a] == Absent) implies s.runs[a] == 0)
  val noOvercharge: bool = s.settled <= s.real
  val safety: bool = runsOnce and appliedOnce and accounted and reservationsHeld and neverAcceptedTrue

  // witnesses
  val done: bool = s.applied == 1
  val restartedThenDone: bool = s.restarts > 0 and s.applied == 1
  val lostAfterRunW: bool = s.lostAfterRun > 0
  val abandonedW: bool = s.abandoned > 0
  val fencedW: bool = s.fenced > 0
  val duplicateW: bool = s.duplicates > 0
  val evictedW: bool = s.evicted > 0
  val refusedW: bool = s.refusals > 0
}
