// -*- mode: Bluespec; -*-
/**
 * engine: the turn commit protocol (docs/cordis/rfc/0006-turn-runtime.md,
 * "The commit record" and "Models to write first").
 *
 * Fibers run turns. A turn is computed in memory and appended to the run
 * store as one record (the fiber's whole state, the turn's intent, any
 * receipt it applies); records become durable a prefix at a time (group
 * commit, and a crash after part of a group); an intent is released to the
 * outbox only from a durable record (the output gate); the outbox sends each
 * key once and answers again from its journal; a receipt is journaled as a
 * record of its own and applied once, however often delivered; a lost
 * receipt makes the fiber Uncertain, durably, until a reconcile. A crash
 * loses what is not durable: the pending records, the in-memory state, any
 * volatile set.
 *
 * A turn's input is nondeterministic (PAYLOADS): it stands for ctx.now, an
 * input's arrival, anything a re-run after a crash may see differently. So
 * a turn re-run after a crash may commit a different intent under the same
 * key, which is what the output gate is for.
 *
 * Properties:
 *  - releasedOnlyDurable: every key the outbox sent is in a durable record;
 *  - effectMatchesCommit: what the outbox sent under a key is what the
 *    durable record under that key says (no effect backed by a record a
 *    crash forgot);
 *  - commitAtomic: a durably Awaiting fiber's intent is durable with it;
 *  - receiptOnce: at most one durable record applies each key's receipt;
 *  - uncertainSurvives: a fiber durably Uncertain leaves Uncertain only by
 *    a reconcile or receipt record.
 * Mutants:
 *  - RELEASE_EARLY (`releaseEarly`): intents released from pending records
 *    -> releasedOnlyDurable, effectMatchesCommit;
 *  - SPLIT_RECORD (`splitRecord`): a turn's state and its intent are two
 *    records -> commitAtomic;
 *  - UNKEYED (`unkeyedReceipt`): a receipt is matched to its fiber by the
 *    fiber alone, not by the intent key it answers, and deduplicated in
 *    memory -> receiptOnce (an old receipt redelivered after a crash is
 *    applied to the turn the fiber now awaits);
 *  - FORGET_UNCERTAIN (`forgetUncertain`): recovery reads Uncertain as
 *    Idle -> uncertainSurvives.
 * Equivalent mutant (checked, expected to hold): DEDUP_IN_MEMORY
 * (`dedupInMemory`), receipts deduplicated against a volatile set rather
 * than the journal. It is harmless because a receipt applies only to a
 * fiber awaiting exactly its key, and the awaited key is durable state: the
 * fiber's record is the dedup. So the run store needs no separate receipt
 * set, provided receipts are matched by key (`unkeyedReceipt`).
 * Witnesses (checked as not(w), expected VIOLATED): crashBeforeRelease (a
 * durable intent not yet sent when a crash struck), groupCommitted,
 * receiptRedelivered, recoveredUncertain, allDone.
 */
module engine {
  const RELEASE_EARLY: bool
  const SPLIT_RECORD: bool
  const DEDUP_IN_MEMORY: bool
  const FORGET_UNCERTAIN: bool
  const UNKEYED: bool

  type Life = Idle | Awaiting | Uncertain | Done

  pure val FIBERS = 2
  pure val TURNS = 2
  pure val PAYLOADS = Set(0, 1)
  pure val NONE = -1

  type Fib = { turn: int, life: Life, payload: int }  // turns emitted; the last intent's payload
  type Record = { fiber: int, f: Fib, intent: int, payload: int, receipt: int }
  type Key = (int, int)          // (fiber, turn): the intent key
  type Sent = (int, int, int)    // (fiber, turn, payload)

  pure val fresh: Fib = { turn: 0, life: Idle, payload: NONE }

  type S = {
    mem: List[Fib],             // in memory
    dur: List[Fib],             // the durable state: the last durable record per fiber
    pending: List[Record],         // appended, not yet durable
    log: List[Record],             // durable records, in order
    durIntents: Set[Sent],
    sent: Set[Key],             // the outbox journal: keys sent (durable, outside the run store)
    sentPayload: Set[Sent],     // ghost: what each send carried
    channel: Set[Key],          // receipts the outbox will deliver, again until acknowledged
    seenMem: Set[Key],          // DEDUP_IN_MEMORY's volatile dedup set
    groups: int, redeliveries: int, crashBeforeSend: int, recUncertain: int,
  }

  var s: S

  action init = s' = {
    mem: [fresh, fresh], dur: [fresh, fresh], pending: List(), log: List(),
    durIntents: Set(), sent: Set(), sentPayload: Set(), channel: Set(), seenMem: Set(),
    groups: 0, redeliveries: 0, crashBeforeSend: 0, recUncertain: 0,
  }

  pure def isAwaiting(l: Life): bool = l == Awaiting

  def pendingIntents: Set[Sent] =
    s.pending.foldl(Set(), (acc, r) => if (r.intent != NONE) acc.union(Set((r.fiber, r.intent, r.payload))) else acc)
  def journaled(k: Key): bool =
    s.log.foldl(false, (b, r) => b or (r.fiber == k._1 and r.receipt == k._2))
    or s.pending.foldl(false, (b, r) => b or (r.fiber == k._1 and r.receipt == k._2))

  // a turn: compute the next intent from the fiber's state and an input
  action turn: bool = {
    nondet n = 0.to(FIBERS - 1).oneOf()
    nondet v = PAYLOADS.oneOf()
    val f = s.mem[n]
    val g = { turn: f.turn + 1, life: Awaiting, payload: v }
    val rec = { fiber: n, f: g, intent: f.turn, payload: v, receipt: NONE }
    all {
      f.life == Idle, f.turn < TURNS,
      s' = { ...s,
        mem: s.mem.replaceAt(n, g),
        pending: if (SPLIT_RECORD) s.pending.append({ ...rec, intent: NONE }).append(rec) else s.pending.append(rec),
      },
    }
  }

  // a prefix of the pending records becomes durable, in one fsync
  action sync: bool = {
    nondet k = 1.to(4).oneOf()
    val m = if (k > s.pending.length()) s.pending.length() else k
    val p = s.pending.slice(0, m)
    all {
      m > 0,
      s' = { ...s,
        log: s.log.concat(p),
        dur: p.foldl(s.dur, (d, r) => d.replaceAt(r.fiber, r.f)),
        durIntents: p.foldl(s.durIntents, (acc, r) => if (r.intent != NONE) acc.union(Set((r.fiber, r.intent, r.payload))) else acc),
        pending: s.pending.slice(m, s.pending.length()),
        groups: if (m >= 2) s.groups + 1 else s.groups,
      },
    }
  }

  // the outbox takes an intent and sends it, once per key; the world runs
  // it, and its receipt may be lost
  action release: bool = {
    val ready = if (RELEASE_EARLY) s.durIntents.union(pendingIntents) else s.durIntents
    nondet i = ready.oneOf()
    nondet delivered = Set(true, false).oneOf()
    all {
      not(s.sent.contains((i._1, i._2))),
      s' = { ...s,
        sent: s.sent.union(Set((i._1, i._2))),
        sentPayload: s.sentPayload.union(Set(i)),
        channel: if (delivered) s.channel.union(Set((i._1, i._2))) else s.channel,
      },
    }
  }

  // a receipt arrives; one already journaled is recognised by its key
  action deliver: bool = {
    nondet k = s.channel.oneOf()
    val f = s.mem[k._1]
    val seen = if (DEDUP_IN_MEMORY or UNKEYED) s.seenMem.contains(k) else journaled(k)
    val waiting = (f.life == Awaiting or f.life == Uncertain) and (UNKEYED or f.turn == k._2 + 1)
    val g = { ...f, life: if (f.turn == TURNS) Done else Idle }
    if (seen) s' = { ...s, redeliveries: s.redeliveries + 1 }
    else all {
      waiting,
      s' = { ...s,
        mem: s.mem.replaceAt(k._1, g),
        pending: s.pending.append({ fiber: k._1, f: g, intent: NONE, payload: NONE, receipt: k._2 }),
        seenMem: s.seenMem.union(Set(k)),
      },
    }
  }

  // the outbox stops redelivering a receipt once it is durable
  action ack: bool = {
    nondet k = s.channel.oneOf()
    all {
      s.log.foldl(false, (b, r) => b or (r.fiber == k._1 and r.receipt == k._2)),
      s' = { ...s, channel: s.channel.exclude(Set(k)) },
    }
  }

  // no receipt in time: the fiber is Uncertain, durably
  action timeout: bool = {
    nondet n = 0.to(FIBERS - 1).oneOf()
    val f = s.mem[n]
    val g = { ...f, life: Uncertain }
    all {
      f.life == Awaiting,
      s' = { ...s, mem: s.mem.replaceAt(n, g), pending: s.pending.append({ fiber: n, f: g, intent: NONE, payload: NONE, receipt: NONE }) },
    }
  }

  // reconcile: the outbox answers from its journal and the world; the
  // answer is journaled as the intent's receipt
  action reconcile: bool = {
    nondet n = 0.to(FIBERS - 1).oneOf()
    val f = s.mem[n]
    val k = (n, f.turn - 1)
    val g = { ...f, life: if (f.turn == TURNS) Done else Idle }
    all {
      f.life == Uncertain, s.sent.contains(k), not(journaled(k)),
      s' = { ...s,
        mem: s.mem.replaceAt(n, g),
        pending: s.pending.append({ fiber: n, f: g, intent: NONE, payload: NONE, receipt: k._2 }),
        seenMem: s.seenMem.union(Set(k)),
      },
    }
  }

  // a crash: memory is rebuilt from the durable records
  action crash: bool = {
    val unsent = s.durIntents.exists(i => not(s.sent.contains((i._1, i._2))))
    val unc = s.dur.foldl(false, (b, f) => b or f.life == Uncertain)
    s' = { ...s,
      mem: s.dur.foldl(List(), (l, f) => l.append(if (FORGET_UNCERTAIN and f.life == Uncertain) { ...f, life: Idle } else f)),
      pending: List(), seenMem: Set(),
      crashBeforeSend: if (unsent) s.crashBeforeSend + 1 else s.crashBeforeSend,
      recUncertain: if (unc) s.recUncertain + 1 else s.recUncertain,
    }
  }

  action step = any { turn, sync, release, deliver, ack, timeout, reconcile, crash }

  // ---- properties -------------------------------------------------------------------------
  val releasedOnlyDurable: bool = s.sent.forall(k => s.durIntents.exists(i => (i._1, i._2) == k))
  val effectMatchesCommit: bool =
    s.sentPayload.forall(x => s.durIntents.forall(i => (i._1, i._2) == (x._1, x._2) implies i._3 == x._3))
  val commitAtomic: bool =
    0.to(FIBERS - 1).forall(n => s.dur[n].life == Awaiting implies s.durIntents.contains((n, s.dur[n].turn - 1, s.dur[n].payload)))
  val receiptOnce: bool =
    0.to(FIBERS - 1).forall(n => 0.to(TURNS - 1).forall(t =>
      s.log.foldl(0, (c, r) => if (r.fiber == n and r.receipt == t) c + 1 else c) <= 1))
  val uncertainSurvives: bool =
    0.to(FIBERS - 1).forall(n => (s.dur[n].life == Uncertain and s.mem[n].life != Uncertain)
      implies s.pending.foldl(false, (b, r) => b or (r.fiber == n and r.receipt != NONE)))
  val safety: bool = releasedOnlyDurable and effectMatchesCommit and commitAtomic and receiptOnce and uncertainSurvives

  // witnesses
  val crashBeforeRelease: bool = s.crashBeforeSend > 0
  val groupCommitted: bool = s.groups > 0
  val receiptRedelivered: bool = s.redeliveries > 0
  val recoveredUncertain: bool = s.recUncertain > 0 and s.mem.foldl(false, (b, f) => b or f.life == Uncertain)
  val allDone: bool = s.dur.foldl(true, (b, f) => b and f.life == Done)
}

module design {
  import engine(RELEASE_EARLY = false, SPLIT_RECORD = false, DEDUP_IN_MEMORY = false, FORGET_UNCERTAIN = false, UNKEYED = false).* from "./engine"
}
module releaseEarly {
  import engine(RELEASE_EARLY = true, SPLIT_RECORD = false, DEDUP_IN_MEMORY = false, FORGET_UNCERTAIN = false, UNKEYED = false).* from "./engine"
}
module splitRecord {
  import engine(RELEASE_EARLY = false, SPLIT_RECORD = true, DEDUP_IN_MEMORY = false, FORGET_UNCERTAIN = false, UNKEYED = false).* from "./engine"
}
module dedupInMemory {
  import engine(RELEASE_EARLY = false, SPLIT_RECORD = false, DEDUP_IN_MEMORY = true, FORGET_UNCERTAIN = false, UNKEYED = false).* from "./engine"
}
module forgetUncertain {
  import engine(RELEASE_EARLY = false, SPLIT_RECORD = false, DEDUP_IN_MEMORY = false, FORGET_UNCERTAIN = true, UNKEYED = false).* from "./engine"
}
module unkeyedReceipt {
  import engine(RELEASE_EARLY = false, SPLIT_RECORD = false, DEDUP_IN_MEMORY = false, FORGET_UNCERTAIN = false, UNKEYED = true).* from "./engine"
}
