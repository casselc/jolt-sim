// -*- mode: Bluespec; -*-
/**
 * budget: reservations and settlement (docs/cordis/rfc/0006-turn-runtime.md,
 * "Decision 4", 4E with the shared limiter; "Models to write first").
 *
 * Fibers of one graph emit intents, each with a worst-case cost W, against
 * the graph's budget B. A turn's record reserves W for its intent in the
 * same record (one write, engine.qnt's commitAtomic), so a turn that would
 * take the reservations over the budget does not run. A receipt carries the
 * actual cost and settles the reservation once, however often it is
 * delivered. A lost receipt makes an effectful intent Uncertain, and its
 * reservation is held until the reconcile says what happened; a reconcile
 * that finds the effect settles at the actual cost, one that finds none
 * releases the reservation. A crash keeps what is durable; the world keeps
 * what it charged.
 *
 * Properties:
 *  - withinBudget: settled plus reserved never exceeds B;
 *  - accounted: what the world charged never exceeds settled plus reserved
 *    (no spend escapes the books, across crashes);
 *  - heldWhileUncertain: an Uncertain intent's reservation is held;
 *  - settleOnce: what is settled is exactly the actual cost of the settled
 *    intents.
 * Mutants:
 *  - RESERVE_AFTER_COMMIT (`reserveAfterCommit`): the reservation is a
 *    second record after the turn's, and a crash can strike between ->
 *    accounted;
 *  - REFUND_ON_LOST (`refundOnLost`): a lost receipt refunds the
 *    reservation -> heldWhileUncertain, and with a reconcile that finds the
 *    effect, accounted;
 *  - SETTLE_EVERY_DELIVERY (`settleEveryDelivery`): a redelivered receipt
 *    settles again -> settleOnce.
 * Witnesses (checked as not(w), expected VIOLATED): refusedForBudget (a turn
 * did not run for lack of budget), settledBelowReservation, uncertainHeld,
 * crashedReserved.
 */
module budget {
  const RESERVE_AFTER_COMMIT: bool
  const REFUND_ON_LOST: bool
  const SETTLE_EVERY_DELIVERY: bool

  pure val B = 5
  pure val W = 2
  pure val INTENTS = 4   // intents 0..3; intent i belongs to fiber i % 2

  type St = Planned | Committed | Uncertain | Settled
  type Book = { st: List[St], reserved: int, settled: int, settledCost: List[int] }

  type S = {
    mem: Book,              // what the engine holds
    dur: Book,              // what its durable records say
    pendingReserve: List[int],  // RESERVE_AFTER_COMMIT: intents whose reservation record is still to come
    ran: List[bool],        // the world ran it (once: the outbox sends each key once)
    cost: List[int],        // what the world charged for it
    channel: Set[int],      // receipts in flight (redelivered until acknowledged)
    refused: int, crashes: int, uncertainSeen: int, below: int,
  }

  var s: S

  pure val start: Book = { st: [Planned, Planned, Planned, Planned], reserved: 0, settled: 0, settledCost: [0, 0, 0, 0] }

  action init = s' = {
    mem: start, dur: start, pendingReserve: List(), ran: [false, false, false, false], cost: [0, 0, 0, 0],
    channel: Set(), refused: 0, crashes: 0, uncertainSeen: 0, below: 0,
  }

  // a turn emits intent i: its record reserves W, if the budget allows
  action emit: bool = {
    nondet i = 0.to(INTENTS - 1).oneOf()
    val m = s.mem
    val fits = m.settled + m.reserved + W <= B
    all {
      m.st[i] == Planned,
      // a fiber's intents in order: i - 2 is the same fiber's previous one
      i < 2 or m.st[i - 2] == Settled,
      if (fits or RESERVE_AFTER_COMMIT) {
        val b = { ...m, st: m.st.replaceAt(i, Committed), reserved: if (RESERVE_AFTER_COMMIT) m.reserved else m.reserved + W }
        s' = { ...s, mem: b, dur: b, pendingReserve: if (RESERVE_AFTER_COMMIT) s.pendingReserve.append(i) else s.pendingReserve }
      } else s' = { ...s, refused: s.refused + 1 },
    }
  }

  // RESERVE_AFTER_COMMIT's second record
  action reserveLate: bool = {
    val b = { ...s.mem, reserved: s.mem.reserved + W }
    all {
      s.pendingReserve.length() > 0,
      s' = { ...s, mem: b, dur: b, pendingReserve: s.pendingReserve.slice(1, s.pendingReserve.length()) },
    }
  }

  // the outbox sends a committed intent, once; the world charges 1..W; the receipt may be lost
  action send: bool = {
    nondet i = 0.to(INTENTS - 1).oneOf()
    nondet c = 1.to(W).oneOf()
    nondet delivered = Set(true, false).oneOf()
    all {
      s.dur.st[i] == Committed or s.dur.st[i] == Uncertain, not(s.ran[i]),
      s' = { ...s, ran: s.ran.replaceAt(i, true), cost: s.cost.replaceAt(i, c),
             channel: if (delivered) s.channel.union(Set(i)) else s.channel },
    }
  }

  // a receipt settles its reservation at the actual cost, once
  action deliver: bool = {
    nondet i = s.channel.oneOf()
    val m = s.mem
    val open = m.st[i] == Committed or m.st[i] == Uncertain
    val b = { ...m, st: m.st.replaceAt(i, Settled),
              reserved: if (open) m.reserved - W else m.reserved,
              settled: m.settled + s.cost[i],
              settledCost: m.settledCost.replaceAt(i, s.cost[i]) }
    all {
      s.channel != Set(),
      open or (SETTLE_EVERY_DELIVERY and m.st[i] == Settled),
      s' = { ...s, mem: b, dur: b, below: if (s.cost[i] < W) s.below + 1 else s.below },
    }
  }

  action ack: bool = {
    nondet i = s.channel.oneOf()
    all { s.channel != Set(), s.dur.st[i] == Settled, s' = { ...s, channel: s.channel.exclude(Set(i)) } }
  }

  // no receipt in time: Uncertain, its reservation held (or, mutant, refunded)
  action timeout: bool = {
    nondet i = 0.to(INTENTS - 1).oneOf()
    val m = s.mem
    val b = { ...m, st: m.st.replaceAt(i, Uncertain), reserved: if (REFUND_ON_LOST) m.reserved - W else m.reserved }
    all {
      m.st[i] == Committed,
      s' = { ...s, mem: b, dur: b, uncertainSeen: s.uncertainSeen + 1 },
    }
  }

  // reconcile: the world says whether it ran; once nothing can still run it, the key is fenced
  action reconcile: bool = {
    nondet i = 0.to(INTENTS - 1).oneOf()
    val m = s.mem
    val held = if (REFUND_ON_LOST) 0 else W
    val b = { ...m, st: m.st.replaceAt(i, Settled), reserved: m.reserved - held,
              settled: m.settled + (if (s.ran[i]) s.cost[i] else 0),
              settledCost: m.settledCost.replaceAt(i, if (s.ran[i]) s.cost[i] else 0) }
    all {
      m.st[i] == Uncertain, not(s.channel.contains(i)),
      s' = { ...s, mem: b, dur: b, ran: s.ran.replaceAt(i, true) },
    }
  }

  // a crash: what is not durable is lost (here, a late reservation not yet written)
  action crash: bool = all {
    s' = { ...s, mem: s.dur, pendingReserve: List(), crashes: s.crashes + 1 },
  }

  action step = any { emit, reserveLate, send, deliver, ack, timeout, reconcile, crash }

  // ---- properties -------------------------------------------------------------------------
  def charged: int = 0.to(INTENTS - 1).fold(0, (a, i) => a + s.cost[i])
  val withinBudget: bool = s.mem.settled + s.mem.reserved <= B
  // pending late reservations are still owed, so not yet a leak
  val accounted: bool = s.pendingReserve.length() > 0 or charged <= s.mem.settled + s.mem.reserved
  val heldWhileUncertain: bool =
    s.mem.reserved >= W * 0.to(INTENTS - 1).filter(i => s.mem.st[i] == Uncertain or s.mem.st[i] == Committed).size()
    or s.pendingReserve.length() > 0
  val settleOnce: bool = s.mem.settled == 0.to(INTENTS - 1).fold(0, (a, i) => a + s.mem.settledCost[i])
  val safety: bool = withinBudget and accounted and heldWhileUncertain and settleOnce

  // witnesses
  val refusedForBudget: bool = s.refused > 0
  val settledBelowReservation: bool = s.below > 0
  val uncertainHeld: bool = s.uncertainSeen > 0 and 0.to(INTENTS - 1).exists(i => s.mem.st[i] == Uncertain)
  val crashedReserved: bool = s.crashes > 0 and s.mem.reserved > 0
}

module design {
  import budget(RESERVE_AFTER_COMMIT = false, REFUND_ON_LOST = false, SETTLE_EVERY_DELIVERY = false).* from "./budget"
}
module reserveAfterCommit {
  import budget(RESERVE_AFTER_COMMIT = true, REFUND_ON_LOST = false, SETTLE_EVERY_DELIVERY = false).* from "./budget"
}
module refundOnLost {
  import budget(RESERVE_AFTER_COMMIT = false, REFUND_ON_LOST = true, SETTLE_EVERY_DELIVERY = false).* from "./budget"
}
module settleEveryDelivery {
  import budget(RESERVE_AFTER_COMMIT = false, REFUND_ON_LOST = false, SETTLE_EVERY_DELIVERY = true).* from "./budget"
}
