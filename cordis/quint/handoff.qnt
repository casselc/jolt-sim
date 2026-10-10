// -*- mode: Bluespec; -*-
/**
 * handoff: the relay from the run store to the outbox journal
 * (docs/cordis/rfc/0006-turn-runtime.md, "Decision 2" and "Models to write
 * first"). The run store rolls back with the agent's step snapshot; the
 * outbox journal does not (outbox.qnt's journalInSnapshot). This model
 * makes the guest side a store and puts a relay and a lossy channel between
 * the two.
 *
 * The guest issues one intent per turn, TURNS of them. A turn is computed
 * (pending), then committed (durable in the run store). The relay sends
 * every committed intent the guest has not seen acknowledged, again
 * whenever it likes; frames are lost and duplicated. The outbox receives a
 * frame, journals the key, acknowledges it, sends it to the world once per
 * key, and answers the guest with a receipt, again for every duplicate.
 * The guest may crash (losing what is pending), and the agent may snapshot
 * its state and later roll back to the snapshot; the outbox may crash
 * (losing what it received and had not journaled). Turns are deterministic,
 * so a guest that rolled back re-derives the same intents; keys are the
 * turn (KEY_FROM_CLOCK mixes in a clock that a rollback does not rewind).
 *
 * Properties:
 *  - atMostOnce: no turn's intent takes effect twice;
 *  - onlyCommittedReleased: every key the outbox journaled was durable in
 *    the run store when it was relayed;
 *  - noOrphan: every key the guest holds acknowledged is journaled;
 *  - receiptToIssuer: the guest applies a receipt only to an intent it has
 *    committed.
 * Mutants:
 *  - KEY_FROM_CLOCK (`keyFromClock`): a key with a time part; a rollback
 *    re-issues under a new key -> atMostOnce;
 *  - RELAY_UNCOMMITTED (`relayUncommitted`): the relay reads records not
 *    yet durable -> onlyCommittedReleased;
 *  - ACK_BEFORE_JOURNAL (`ackBeforeJournal`): the outbox acknowledges on
 *    receipt, journals after, and crashes between -> noOrphan;
 *  - RECEIPT_ANY (`receiptAny`): a receipt is applied whether or not the
 *    guest (after a rollback) has re-issued its intent -> receiptToIssuer.
 * Witnesses (checked as not(w), expected VIOLATED): rolledBackReissued (a
 * re-issued key answered from the journal), duplicateFrameIgnored,
 * resentAfterOutboxCrash, allAnswered.
 */
module handoff {
  const KEY_FROM_CLOCK: bool
  const RELAY_UNCOMMITTED: bool
  const ACK_BEFORE_JOURNAL: bool
  const RECEIPT_ANY: bool

  pure val TURNS = 3
  pure def logical(k: int): int = k % 10

  type Guest = { turn: int, committed: Set[int], acked: Set[int], receipts: Set[int] }
  pure val start: Guest = { turn: 0, committed: Set(), acked: Set(), receipts: Set() }

  type S = {
    g: Guest,                 // the run store: rolls back with the snapshot
    pending: Set[int],        // computed, not durable (lost by a guest crash)
    snap: Guest,              // the step snapshot
    clock: int,               // never rewound
    channel: Set[int],        // frames in flight to the outbox
    received: Set[int],       // the outbox's, not yet journaled (lost by an outbox crash)
    journal: Set[int],        // the outbox journal: outside the snapshot
    sentWorld: Set[int],      // keys the outbox sent to the world
    world: List[int],         // ghost: effects per logical turn
    back: Set[int],           // receipts in flight to the guest
    everDurable: Set[int],    // ghost: keys durable in the run store at some point
    reissued: int, dups: int, resent: int, outboxCrashes: int, rollbacks: int,
  }

  var s: S

  action init = s' = {
    g: start, pending: Set(), snap: start, clock: 0, channel: Set(), received: Set(), journal: Set(),
    sentWorld: Set(), world: [0, 0, 0], back: Set(), everDurable: Set(),
    reissued: 0, dups: 0, resent: 0, outboxCrashes: 0, rollbacks: 0,
  }

  // the guest computes its next turn's intent
  action compute: bool = {
    val k = if (KEY_FROM_CLOCK) 10 * (s.clock + 1) + s.g.turn else s.g.turn
    all {
      s.g.turn < TURNS, s.pending == Set(),
      s' = { ...s, pending: Set(k), g: { ...s.g, turn: s.g.turn + 1 }, clock: s.clock + 1 },
    }
  }

  // the turn's record becomes durable in the run store
  action commit: bool = all {
    s.pending != Set(),
    s' = { ...s, g: { ...s.g, committed: s.g.committed.union(s.pending) }, pending: Set(),
           everDurable: s.everDurable.union(s.pending) },
  }

  // the relay sends a committed, unacknowledged intent (again, if it likes)
  action relay: bool = {
    val ready = (if (RELAY_UNCOMMITTED) s.g.committed.union(s.pending) else s.g.committed).exclude(s.g.acked)
    nondet k = ready.oneOf()
    all {
      ready != Set(),
      s' = { ...s, channel: s.channel.union(Set(k)),
             resent: if (s.outboxCrashes > 0 and not(s.journal.contains(k))) s.resent + 1 else s.resent },
    }
  }

  action loseFrame: bool = {
    nondet k = s.channel.oneOf()
    all { s.channel != Set(), s' = { ...s, channel: s.channel.exclude(Set(k)) } }
  }

  // the outbox receives a frame (keeping a copy in the channel models a
  // duplicate); a journaled key is answered from the journal
  action receive: bool = {
    nondet k = s.channel.oneOf()
    nondet dup = Set(true, false).oneOf()
    val known = s.journal.contains(k)
    all {
      s.channel != Set(),
      s' = { ...s,
        channel: if (dup) s.channel else s.channel.exclude(Set(k)),
        received: if (known) s.received else s.received.union(Set(k)),
        g: if (ACK_BEFORE_JOURNAL or known) { ...s.g, acked: s.g.acked.union(Set(k)) } else s.g,
        back: if (known and s.sentWorld.contains(k)) s.back.union(Set(k)) else s.back,
        dups: if (known) s.dups + 1 else s.dups,
        reissued: if (known and s.rollbacks > 0 and s.sentWorld.contains(k)) s.reissued + 1 else s.reissued,
      },
    }
  }

  // the outbox journals what it received, then acknowledges
  action journalIt: bool = {
    nondet k = s.received.oneOf()
    all {
      s.received != Set(),
      s' = { ...s, received: s.received.exclude(Set(k)), journal: s.journal.union(Set(k)),
             g: { ...s.g, acked: s.g.acked.union(Set(k)) } },
    }
  }

  // the outbox sends a journaled key to the world, once, and answers
  action send: bool = {
    nondet k = s.journal.oneOf()
    all {
      s.journal != Set(), not(s.sentWorld.contains(k)),
      s' = { ...s, sentWorld: s.sentWorld.union(Set(k)),
             world: s.world.replaceAt(logical(k), s.world[logical(k)] + 1),
             back: s.back.union(Set(k)) },
    }
  }

  // a receipt reaches the guest; one for an intent it has not (re-)issued waits
  action deliver: bool = {
    nondet k = s.back.oneOf()
    all {
      s.back != Set(),
      RECEIPT_ANY or s.g.committed.contains(k),
      s' = { ...s, back: s.back.exclude(Set(k)), g: { ...s.g, receipts: s.g.receipts.union(Set(k)) } },
    }
  }

  action guestCrash: bool = all {
    s' = { ...s, pending: Set(), g: { ...s.g, turn: s.g.turn - s.pending.size() } },
  }

  action outboxCrash: bool = all {
    s.received != Set(),
    s' = { ...s, received: Set(), outboxCrashes: s.outboxCrashes + 1 },
  }

  action snapshot: bool = all { s.pending == Set(), s' = { ...s, snap: s.g } }

  action rollback: bool = all {
    s' = { ...s, g: s.snap, pending: Set(), rollbacks: s.rollbacks + 1 },
  }

  action step = any { compute, commit, relay, loseFrame, receive, journalIt, send, deliver, guestCrash, outboxCrash, snapshot, rollback }

  // ---- properties -------------------------------------------------------------------------
  val atMostOnce: bool = s.world.foldl(true, (b, n) => b and n <= 1)
  val onlyCommittedReleased: bool = s.journal.subseteq(s.everDurable)
  val noOrphan: bool = s.g.acked.subseteq(s.journal)
  val receiptToIssuer: bool = s.g.receipts.subseteq(s.g.committed)
  val safety: bool = atMostOnce and onlyCommittedReleased and noOrphan and receiptToIssuer

  // witnesses
  val rolledBackReissued: bool = s.reissued > 0
  val duplicateFrameIgnored: bool = s.dups > 0
  val resentAfterOutboxCrash: bool = s.resent > 0 and s.journal.size() > 0
  val allAnswered: bool = s.g.receipts.size() == TURNS
}

module design {
  import handoff(KEY_FROM_CLOCK = false, RELAY_UNCOMMITTED = false, ACK_BEFORE_JOURNAL = false, RECEIPT_ANY = false).* from "./handoff"
}
module keyFromClock {
  import handoff(KEY_FROM_CLOCK = true, RELAY_UNCOMMITTED = false, ACK_BEFORE_JOURNAL = false, RECEIPT_ANY = false).* from "./handoff"
}
module relayUncommitted {
  import handoff(KEY_FROM_CLOCK = false, RELAY_UNCOMMITTED = true, ACK_BEFORE_JOURNAL = false, RECEIPT_ANY = false).* from "./handoff"
}
module ackBeforeJournal {
  import handoff(KEY_FROM_CLOCK = false, RELAY_UNCOMMITTED = false, ACK_BEFORE_JOURNAL = true, RECEIPT_ANY = false).* from "./handoff"
}
module receiptAny {
  import handoff(KEY_FROM_CLOCK = false, RELAY_UNCOMMITTED = false, ACK_BEFORE_JOURNAL = false, RECEIPT_ANY = true).* from "./handoff"
}
