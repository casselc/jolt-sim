// -*- mode: Bluespec; -*-
/**
 * frames: a cell consuming a frame in batches, one turn per batch
 * (docs/cordis/rfc/0005-xml-csv-frames.md, "Frames"; the prior art is
 * docs/cordis/research/STREAMING-PRIOR-ART.md).
 *
 * One frame input of N rows; the cell folds them into an accumulator and
 * emits one intent per batch and one at the end. The runtime chooses each
 * batch's size, at most the policy ceiling B, and logs the batch's
 * interval [lo, hi) durably before its turn runs; at most CAPB planned
 * batches wait unconsumed. Revisions may be admitted mid-frame, by one of
 * three modes: carry (the accumulator crosses, only when the new revision
 * reads its type), drain (the frame finishes on the old revision; the next
 * one runs on the new) and restart (a new generation: the accumulator is
 * dropped and the frame re-read from 0). A new input abandons the frame.
 *
 * Revisions: 0 folds x; 1 folds 2x (same accumulator type, new meaning);
 * 2 folds x into an accumulator of another type.
 *
 * What the design does, and what it protects:
 *  - commit is one atomic record {accumulator, cursor, revision, intents}:
 *    a turn computed but not committed is lost to a crash and re-run;
 *  - accConsistent: the accumulator is the fold of the committed batches,
 *    each under the revision recorded in its commit (so a carried
 *    accumulator is "the old revision on [0,k), the new one on [k,..)",
 *    not a cold start of the new one);
 *  - contiguous: the committed batches partition [0, cursor) in order;
 *  - revsInOrder: cutover happens at a commit boundary, never mid-batch;
 *  - accTyped: the accumulator is of the type the live revision reads;
 *  - effectsOnce: an intent, keyed (generation, lo), is observed once,
 *    however often the outbox redelivers it;
 *  - endAfterAll: the end turn runs only after the last batch commits;
 *  - noRowLost: a full buffer stops the reader; it never skips rows;
 *  - bufferBounded: at most CAPB planned batches wait.
 * Mutants:
 *  - SPLIT_COMMIT (`splitCommit`): the cursor is written after the state,
 *    and a crash between re-runs a committed batch -> contiguous;
 *  - MIDBATCH (`midBatch`): a cutover keeps the turn computed by the old
 *    revision and commits it under the new -> accConsistent;
 *  - CARRY_ANY (`carryAny`): carry across a change of accumulator type
 *    -> accTyped;
 *  - DEDUP = false (`noDedup`): the receiver keeps no receipts
 *    -> effectsOnce;
 *  - END_EARLY (`endEarly`): the end turn runs once every row is planned,
 *    not committed -> endAfterAll;
 *  - REFUSE_ADVANCES (`refuseAdvances`): a full buffer refuses a batch but
 *    the reader's position passes it -> noRowLost;
 *  - KEEP_ACC (`keepAcc`): a new input keeps the accumulator
 *    -> accConsistent.
 * Witnesses (checked as not(w), expected VIOLATED): someEnded, carried (a
 * frame's commits under two revisions), drained, restarted, redelivered
 * (an intent delivered twice, observed once), bufferFull, abandoned.
 */
module frames {
  const SPLIT_COMMIT: bool
  const MIDBATCH: bool
  const CARRY_ANY: bool
  const DEDUP: bool
  const END_EARLY: bool
  const REFUSE_ADVANCES: bool
  const KEEP_ACC: bool

  pure val N = 4
  pure val B = 2
  pure val CAPB = 1
  pure val EPISODES = 2
  pure val REVS = Set(0, 1, 2)
  pure val NONE = -1

  pure def typeOf(r: int): int = if (r == 2) 1 else 0
  pure def f(r: int, x: int): int = if (r == 1) 2 * x else x
  pure def row(ep: int, i: int): int = 10 * ep + i + 1
  pure def batchSum(r: int, ep: int, lo: int, hi: int): int = 0.to(N - 1).fold(0, (a, i) => if (lo <= i and i < hi) a + f(r, row(ep, i)) else a)

  type Batch = { lo: int, hi: int, rev: int }
  type Turn = { valid: bool, lo: int, hi: int, acc: int, rev: int }
  type Key = (int, int) // (generation, lo); the end turn's lo is N

  pure val noTurn: Turn = { valid: false, lo: 0, hi: 0, acc: 0, rev: NONE }

  type S = {
    ep: int,              // the input's episode (a new input abandons the frame)
    gen: int,             // the generation, which intent keys carry
    live: int,            // the live revision
    waiting: int,         // a revision admitted by drain, not yet live
    plans: List[Batch],   // durable: the planned intervals, rev unused
    readPos: int,         // the reader's position (the last plan's hi)
    lost: Set[int],       // rows the reader passed without planning (a bug)
    acc: int,             // committed: the accumulator
    accType: int,         // committed: its type
    cursor: int,          // committed: rows consumed
    ended: bool,          // committed: the end turn ran
    log: List[Batch],     // ghost: the committed batches, with their revision
    turn: Turn,           // volatile: a turn computed, not committed
    cursorDue: int,       // SPLIT_COMMIT only: the cursor the next write sets
    staged: Set[Key],     // durable: the outbox
    acked: Set[Key],      // durable: intents the outbox knows were received
    seen: Set[Key],       // durable: the receiver's receipts
    observed: List[Key],  // ghost: the effects as the world saw them
    carries: int, drains: int, restarts: int, redeliveries: int, fulls: int, abandons: int,
  }

  var s: S

  def buffered(t: S): int = t.plans.select(p => p.lo >= t.cursor).length()

  action init = s' = {
    ep: 0, gen: 0, live: 0, waiting: NONE, plans: List(), readPos: 0, lost: Set(),
    acc: 0, accType: 0, cursor: 0, ended: false, log: List(), turn: noTurn, cursorDue: NONE,
    staged: Set(), acked: Set(), seen: Set(), observed: List(),
    carries: 0, drains: 0, restarts: 0, redeliveries: 0, fulls: 0, abandons: 0,
  }

  // the reader plans the next batch, its size the runtime's choice up to B;
  // the plan is durable before any turn reads it
  action plan: bool = {
    nondet k = 1.to(B).oneOf()
    val hi = if (s.readPos + k > N) N else s.readPos + k
    val full = buffered(s) >= CAPB
    all {
      s.readPos < N,
      not(full) or REFUSE_ADVANCES,
      s' = if (full) { ...s, readPos: hi, lost: s.lost.union(0.to(N - 1).filter(i => s.readPos <= i and i < hi)), fulls: s.fulls + 1 }
           else { ...s, plans: s.plans.append({ lo: s.readPos, hi: hi, rev: NONE }), readPos: hi },
    }
  }

  // a full buffer: the reader waits (a witness that the cap is met)
  action waitFull: bool = all {
    s.readPos < N, buffered(s) >= CAPB, not(REFUSE_ADVANCES),
    s' = { ...s, fulls: s.fulls + 1 },
  }

  // a turn: the planned batch at the cursor, folded by the live revision
  action runTurn: bool = {
    nondet i = s.plans.indices().oneOf()
    val p = s.plans[i]
    all {
      not(s.turn.valid), s.cursorDue == NONE, not(s.ended), p.lo == s.cursor,
      s' = { ...s, turn: { valid: true, lo: p.lo, hi: p.hi, acc: s.acc + batchSum(s.live, s.ep, p.lo, p.hi), rev: s.live } },
    }
  }

  // the commit: one record of state, cursor, revision and intents. A turn
  // computed by a revision no longer live is not committed (the design
  // discards it at cutover; MIDBATCH keeps it).
  action commit: bool = {
    val t = s.turn
    val rec = if (MIDBATCH) s.live else t.rev
    all {
      t.valid,
      s' = { ...s,
        acc: t.acc,
        cursor: if (SPLIT_COMMIT) s.cursor else t.hi,
        cursorDue: if (SPLIT_COMMIT) t.hi else NONE,
        log: s.log.append({ lo: t.lo, hi: t.hi, rev: rec }),
        staged: s.staged.union(Set((s.gen, t.lo))),
        turn: noTurn,
      },
    }
  }

  // SPLIT_COMMIT's second write
  action writeCursor: bool = all { s.cursorDue != NONE, s' = { ...s, cursor: s.cursorDue, cursorDue: NONE } }

  // a crash loses what is volatile: the uncommitted turn, a pending cursor write
  action crash: bool = s' = { ...s, turn: noTurn, cursorDue: NONE }

  // the end turn: after the last batch commits, once; itself a commit
  action endTurn: bool = all {
    not(s.ended), not(s.turn.valid), s.cursorDue == NONE,
    if (END_EARLY) s.readPos == N else s.cursor == N,
    s' = { ...s, ended: true, staged: s.staged.union(Set((s.gen, N))) },
  }

  // admission of a later revision, by mode
  action admit: bool = {
    nondet r = REVS.oneOf()
    nondet mode = Set("carry", "drain", "restart").oneOf()
    all {
      r > s.live, s.waiting == NONE, r > s.waiting,
      if (mode == "carry") all {
        CARRY_ANY or typeOf(r) == typeOf(s.live),
        // cutover at a commit boundary: an uncommitted turn is dropped
        s' = { ...s, live: r, turn: if (MIDBATCH) s.turn else noTurn, carries: s.carries + 1 },
      } else if (mode == "drain")
        s' = { ...s, waiting: r }
      else
        s' = { ...s, live: r, gen: s.gen + 1, plans: List(), readPos: 0, acc: 0, accType: typeOf(r),
               cursor: 0, ended: false, log: List(), turn: noTurn, cursorDue: NONE, restarts: s.restarts + 1 },
    }
  }

  // a new input: the frame is abandoned (or done), and the next one runs
  // on a drained revision
  action newInput: bool = {
    val nextRev = if (s.waiting != NONE) s.waiting else s.live
    all {
      s.ep + 1 < EPISODES,
      s' = { ...s, ep: s.ep + 1, gen: s.gen + 1, live: nextRev, waiting: NONE, plans: List(), readPos: 0,
             acc: if (KEEP_ACC) s.acc else 0, accType: typeOf(nextRev), cursor: 0, ended: false, log: List(),
             turn: noTurn, cursorDue: NONE,
             drains: if (s.waiting != NONE) s.drains + 1 else s.drains,
             abandons: if (s.ended) s.abandons else s.abandons + 1 },
    }
  }

  // the outbox delivers a staged intent, at least once; the receiver's
  // receipts make it observed once
  action deliver: bool = {
    nondet k = s.staged.oneOf()
    val again = s.seen.contains(k)
    all {
      not(s.acked.contains(k)),
      s' = { ...s,
        observed: if (DEDUP and again) s.observed else s.observed.append(k),
        seen: s.seen.union(Set(k)),
        redeliveries: if (again) s.redeliveries + 1 else s.redeliveries,
      },
    }
  }

  action ack: bool = {
    nondet k = s.staged.oneOf()
    all { s.seen.contains(k), not(s.acked.contains(k)), s' = { ...s, acked: s.acked.union(Set(k)) } }
  }

  action step = any { plan, waitFull, runTurn, commit, writeCursor, crash, endTurn, admit, newInput, deliver, ack }

  // ---- properties -------------------------------------------------------------------------
  val accConsistent: bool = s.cursorDue != NONE or s.acc == s.log.foldl(0, (a, b) => a + batchSum(b.rev, s.ep, b.lo, b.hi))
  val contiguous: bool = s.log.indices().forall(i => s.log[i].lo == (if (i == 0) 0 else s.log[i - 1].hi))
  val cursorMatches: bool = s.cursorDue != NONE or s.cursor == (if (s.log.length() == 0) 0 else s.log[s.log.length() - 1].hi)
  val revsInOrder: bool = s.log.indices().forall(i => i == 0 or s.log[i - 1].rev <= s.log[i].rev)
  val accTyped: bool = s.accType == typeOf(s.live)
  val effectsOnce: bool = s.observed.indices().forall(i => s.observed.indices().forall(j => i == j or s.observed[i] != s.observed[j]))
  val endAfterAll: bool = s.ended implies s.cursor == N
  val noRowLost: bool = s.lost == Set()
  val bufferBounded: bool = buffered(s) <= CAPB
  val safety: bool = accConsistent and contiguous and cursorMatches and revsInOrder and accTyped
    and effectsOnce and endAfterAll and noRowLost and bufferBounded

  // witnesses
  val someEnded: bool = s.ended and s.acc > 0
  val carried: bool = s.log.indices().exists(i => s.log[i].rev != s.log[0].rev)
  val drained: bool = s.drains > 0 and s.cursor > 0
  val restarted: bool = s.restarts > 0 and s.ended
  val redelivered: bool = s.redeliveries > 0
  val bufferFull: bool = s.fulls > 0
  val abandoned: bool = s.abandons > 0
}

module design {
  import frames(SPLIT_COMMIT = false, MIDBATCH = false, CARRY_ANY = false, DEDUP = true,
                END_EARLY = false, REFUSE_ADVANCES = false, KEEP_ACC = false).* from "./frames"
}
module splitCommit {
  import frames(SPLIT_COMMIT = true, MIDBATCH = false, CARRY_ANY = false, DEDUP = true,
                END_EARLY = false, REFUSE_ADVANCES = false, KEEP_ACC = false).* from "./frames"
}
module midBatch {
  import frames(SPLIT_COMMIT = false, MIDBATCH = true, CARRY_ANY = false, DEDUP = true,
                END_EARLY = false, REFUSE_ADVANCES = false, KEEP_ACC = false).* from "./frames"
}
module carryAny {
  import frames(SPLIT_COMMIT = false, MIDBATCH = false, CARRY_ANY = true, DEDUP = true,
                END_EARLY = false, REFUSE_ADVANCES = false, KEEP_ACC = false).* from "./frames"
}
module noDedup {
  import frames(SPLIT_COMMIT = false, MIDBATCH = false, CARRY_ANY = false, DEDUP = false,
                END_EARLY = false, REFUSE_ADVANCES = false, KEEP_ACC = false).* from "./frames"
}
module endEarly {
  import frames(SPLIT_COMMIT = false, MIDBATCH = false, CARRY_ANY = false, DEDUP = true,
                END_EARLY = true, REFUSE_ADVANCES = false, KEEP_ACC = false).* from "./frames"
}
module refuseAdvances {
  import frames(SPLIT_COMMIT = false, MIDBATCH = false, CARRY_ANY = false, DEDUP = true,
                END_EARLY = false, REFUSE_ADVANCES = true, KEEP_ACC = false).* from "./frames"
}
module keepAcc {
  import frames(SPLIT_COMMIT = false, MIDBATCH = false, CARRY_ANY = false, DEDUP = true,
                END_EARLY = false, REFUSE_ADVANCES = false, KEEP_ACC = true).* from "./frames"
}
