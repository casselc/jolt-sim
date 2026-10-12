// -*- mode: Bluespec; -*-
/**
 * capture: the platform's durable handoff of an operation's result
 * (docs/cordis/rfc/0007-boundaries.md, "The durable handoff").
 *
 * operation.qnt takes capture as one step. It is four, and the order is
 * the protocol:
 *
 *   fetch the result from the executor
 *   -> commit it durably in the platform (receipt and settlement, one
 *      record, with a pending delivery)
 *   -> acknowledge the executor, which then releases it
 *   -> deliver the receipt to the workflow, which applies it by its key
 *      and confirms, and only then is the delivery no longer pending.
 *
 * The platform may crash between any two (what is in memory is lost, what
 * is committed is not), an acknowledgement or a delivery may be lost or
 * duplicated, and the executor may restart and forget the result. A lost
 * acknowledgement must only retain the result longer, never lose the
 * receipt.
 *
 * Properties:
 *  - resultNotLost: the executor never releases a result the platform has
 *    not committed;
 *  - receiptNotLost: a committed receipt is applied, or its delivery is
 *    still pending;
 *  - appliedOnce: the workflow applies at most one receipt;
 *  - accounted: real spend never exceeds settled plus reserved spend.
 * Mutants:
 *  - ACK_FIRST (`ackFirst`): the platform acknowledges as soon as it has
 *    fetched, before committing -> resultNotLost (a crash between loses
 *    work that was paid for);
 *  - CLEAR_EARLY (`clearEarly`): the delivery is no longer pending once
 *    sent, not once the workflow confirms -> receiptNotLost;
 *  - NO_KEY (`noKey`): the workflow applies every delivery, not one per
 *    key -> appliedOnce.
 * Witnesses (checked as not(w), expected VIOLATED): done, ackLostThenAcked,
 * crashedUnacked (a crash after the commit, before the executor was
 * acknowledged), duplicateDelivered, executorForgot.
 */
module capture {
  const ACK_FIRST: bool
  const CLEAR_EARLY: bool
  const NO_KEY: bool

  pure val R = 2          // the attempt's worst-case cost: its reservation
  pure val MAXM = 2       // messages of a kind in flight

  type S = {
    // the executor
    hHolds: bool, hRan: bool, hCost: int, hReleased: bool,
    // the platform: in memory, and committed
    fetched: bool,
    captured: bool, pending: bool, ackDone: bool, lost: bool,
    reserved: int, settled: int,
    real: int,                  // ghost: what the executor actually spent
    // the network, and the workflow
    acks: int, recs: int, applied: int, deliveries: int,
    lostAcks: int, crashedUnacked: int, restarts: int,
  }

  var s: S

  action init = s' = {
    hHolds: true, hRan: false, hCost: 0, hReleased: false,
    fetched: false, captured: false, pending: false, ackDone: false, lost: false,
    reserved: R, settled: 0, real: 0,
    acks: 0, recs: 0, applied: 0, deliveries: 0,
    lostAcks: 0, crashedUnacked: 0, restarts: 0,
  }

  // the executor finishes the attempt, spending 1..R
  action haloRuns: bool = {
    nondet c = 1.to(R).oneOf()
    all { s.hHolds, not(s.hRan), s' = { ...s, hRan: true, hCost: c, real: s.real + c } }
  }

  // the platform reads the result: in memory only
  action fetch: bool = all {
    s.hHolds, s.hRan, not(s.captured), not(s.lost), not(s.fetched),
    s' = { ...s, fetched: true },
  }

  // ...commits it: receipt, settlement and a pending delivery, one durable record
  action commit: bool = all {
    s.fetched, not(s.captured), not(s.lost),
    s' = { ...s, captured: true, pending: true, fetched: false, settled: s.hCost, reserved: 0 },
  }

  // ...acknowledges the executor, once committed (ACK_FIRST: once fetched)
  action sendAck: bool = all {
    s.captured or (ACK_FIRST and s.fetched), not(s.ackDone), s.acks < MAXM,
    s' = { ...s, acks: s.acks + 1 },
  }

  // an acknowledgement arrives: the executor releases a result it holds
  action deliverAck: bool = all {
    s.acks > 0,
    s' = { ...s, acks: s.acks - 1, hHolds: false, hReleased: if (s.hHolds) true else s.hReleased },
  }

  action loseAck: bool = all { s.acks > 0, s' = { ...s, acks: s.acks - 1, lostAcks: s.lostAcks + 1 } }

  // the platform learns the executor released it (or no longer holds it: nothing to acknowledge)
  action confirmAck: bool = all { s.captured, not(s.ackDone), s.hReleased or not(s.hHolds), s' = { ...s, ackDone: true } }

  // the committed receipt is sent to the workflow, as often as it is pending
  action sendReceipt: bool = all {
    s.captured, s.pending, s.recs < MAXM,
    s' = { ...s, recs: s.recs + 1, pending: if (CLEAR_EARLY) false else s.pending },
  }

  action loseReceipt: bool = all { s.recs > 0, s' = { ...s, recs: s.recs - 1 } }

  // a receipt arrives: the workflow applies it once by its key (NO_KEY: every time)
  action deliverReceipt: bool = all {
    s.recs > 0,
    s' = { ...s, recs: s.recs - 1, deliveries: s.deliveries + 1,
           applied: if (NO_KEY) s.applied + 1 else if (s.applied == 0) 1 else s.applied },
  }

  // the workflow's confirmation reaches the platform: only now is the delivery no longer pending
  action confirmApplied: bool = all { s.pending, s.applied > 0, s' = { ...s, pending: false } }

  // the platform crashes: what is in memory is lost, what is committed is not
  action crash: bool = all {
    s' = { ...s, fetched: false,
           crashedUnacked: if (s.captured and not(s.ackDone)) s.crashedUnacked + 1 else s.crashedUnacked },
  }

  // the executor restarts: it holds nothing
  action haloRestart: bool = all {
    s' = { ...s, hHolds: false, hRan: false, restarts: s.restarts + 1 },
  }

  // an uncommitted attempt the executor no longer holds: unknown, settled at the worst case
  action unknownSettle: bool = all {
    not(s.captured), not(s.lost), not(s.hHolds),
    s' = { ...s, lost: true, reserved: 0, settled: R },
  }

  action idle: bool = s' = s

  action step = any { haloRuns, fetch, commit, sendAck, deliverAck, loseAck, confirmAck, sendReceipt, loseReceipt,
                      deliverReceipt, confirmApplied, crash, haloRestart, unknownSettle, idle }

  // ---- properties -------------------------------------------------------------------------
  val resultNotLost: bool = s.hReleased implies s.captured
  val receiptNotLost: bool = s.captured implies (s.applied > 0 or s.pending)
  val appliedOnce: bool = s.applied <= 1
  val accounted: bool = s.real <= s.settled + s.reserved
  val safety: bool = resultNotLost and receiptNotLost and appliedOnce and accounted

  // witnesses
  val done: bool = s.applied == 1 and s.ackDone and not(s.pending)
  val ackLostThenAcked: bool = s.lostAcks > 0 and s.ackDone and s.hReleased
  val crashedUnackedW: bool = s.crashedUnacked > 0
  val duplicateDelivered: bool = s.deliveries >= 2
  val executorForgot: bool = s.restarts > 0 and s.lost
}
