// -*- mode: Bluespec; -*-
/**
 * outbox: where emissions leave the system (docs/cordis/DESIGN.md, "Where
 * the outbox runs"). Each agent has an outbox jail that holds its journal,
 * its credentials, its retries and its reconcile. A central limit service
 * next to `pf` grants tokens against a limit shared by every agent, and holds
 * no credentials. `pf` lets traffic out of the system only from outbox jails.
 * The external service applies each copy it receives (it is not idempotent),
 * and its answers, like every message here, can be lost.
 *
 * Emission i belongs to agent owner(i) = i / 10. The guest asks for it
 * (`issue`); the outbox journals it, asks the limiter for a token, sends it
 * with the agent's credential, and records the receipt. Three things can go
 * wrong underneath it:
 *  - the outbox jail crashes and restarts: whatever it had sent and not yet
 *    heard about becomes Unknown, and is reconciled (read back once no copy
 *    can still land), never blindly resent;
 *  - the agent's step snapshot is rolled back: the guest rewinds and asks
 *    for the same emissions again under the same keys; the journal, kept on
 *    a dataset that step snapshots skip, answers from what it recorded;
 *  - the limiter's messages are lost or duplicated: requests are resent,
 *    and the limiter grants each emission at most one token.
 *
 * Properties:
 *  - atMostOnce: no emission takes effect twice;
 *  - withinLimit: no more emissions take effect, across all agents, than
 *    the shared LIMIT;
 *  - onlyOutboxEgress: every request that reached the service came from an
 *    outbox jail;
 *  - receiptsTrue: a Done receipt never claims an effect that did not happen.
 * Mutants:
 *  - JOURNAL_SNAPSHOTTED (`journalInSnapshot`): the journal lives on the
 *    agent's snapshotted dataset, so a rollback rewinds it -> atMostOnce;
 *  - CENTRAL = false (`localLimits`): each jail enforces the limit on its
 *    own emissions -> withinLimit;
 *  - PF_OPEN (`pfOpen`): a tool jail can reach the service directly, with no
 *    journal and no token -> onlyOutboxEgress, and with it atMostOnce;
 *  - RECONCILE = false (`resendOnRestart`): a restarted outbox resends what
 *    it had sent -> atMostOnce.
 * Witnesses (checked as not(w), expected VIOLATED): someUnknown, limitFull,
 * reconciledApplied, reconciledNotApplied, rolledBackAfterSend.
 */
module outbox {
  type Entry =
    | New        // not asked for yet (or, after a mutant rollback, forgotten)
    | WaitGrant  // journaled; waiting for the limiter's token
    | Ready      // holds a token; may send
    | Sent       // a copy is out; waiting for the answer
    | Unknown    // the jail restarted while Sent: reconcile before anything else
    | Done       // the effect happened

  type Req = { i: int, attempt: int, src: str }

  const AGENTS: Set[int]
  const IDS: Set[int]
  const LIMIT: int
  const JOURNAL_SNAPSHOTTED: bool
  const CENTRAL: bool
  const PF_OPEN: bool
  const RECONCILE: bool

  pure def owner(i: int): int = i / 10

  // the outbox jails
  var journal: int -> Entry          // durable, unless JOURNAL_SNAPSHOTTED
  var snap: int -> Entry             // the journal as the last step snapshot holds it
  var up: int -> bool
  var attempts: int -> int
  var reconciled: Set[(int, bool)]   // (emission, did it take effect) at each reconcile
  var rolledBack: Set[int]           // emissions that were Sent or Done when a rollback hit
  // the central limit service
  var granted: Set[int]
  var reqs: Set[int]                 // token requests in flight
  var grants: Set[int]               // token grants in flight
  // the external service
  var inflight: Set[Req]
  var applied: int -> int
  var answers: Set[int]              // "done" answers in flight
  var egress: Set[Req]               // every request that ever reached the service

  action init = all {
    journal' = IDS.mapBy(_ => New), snap' = IDS.mapBy(_ => New),
    up' = AGENTS.mapBy(_ => true), attempts' = IDS.mapBy(_ => 0),
    reconciled' = Set(), rolledBack' = Set(),
    granted' = Set(), reqs' = Set(), grants' = Set(),
    inflight' = Set(), applied' = IDS.mapBy(_ => 0), answers' = Set(), egress' = Set(),
  }

  action keepJail = all { snap' = snap, up' = up, attempts' = attempts, reconciled' = reconciled, rolledBack' = rolledBack }
  action keepLimiter = all { granted' = granted, reqs' = reqs, grants' = grants }
  action keepService = all { inflight' = inflight, applied' = applied, answers' = answers, egress' = egress }

  def mine(a: int): Set[int] = IDS.filter(i => owner(i) == a)
  def tokensHeld(a: int): int = mine(a).filter(i => journal.get(i) != New and journal.get(i) != WaitGrant).size()
  def copyOut(i: int): bool = inflight.exists(q => q.i == i)

  // ---- the guest asks; the outbox journals --------------------------------------------
  // A request for an emission the journal already holds is answered from the
  // journal (the rollback case): nothing is sent, nothing changes.
  action issue(i: int): bool = all {
    up.get(owner(i)), journal.get(i) == New,
    journal' = journal.set(i, WaitGrant),
    reqs' = if (CENTRAL) reqs.union(Set(i)) else reqs,
    granted' = granted, grants' = grants, keepJail, keepService,
  }

  // ---- the limiter ----------------------------------------------------------------------
  // A token request is answered at most once per emission: a duplicate (a
  // resend after a lost grant) gets the same grant again, not a second token.
  action grant(i: int): bool = all {
    CENTRAL, reqs.contains(i), granted.contains(i) or granted.size() < LIMIT,
    granted' = granted.union(Set(i)), reqs' = reqs.exclude(Set(i)), grants' = grants.union(Set(i)),
    journal' = journal, keepJail, keepService,
  }

  action loseReq(i: int): bool = all {
    reqs.contains(i), reqs' = reqs.exclude(Set(i)),
    granted' = granted, grants' = grants, journal' = journal, keepJail, keepService,
  }

  action loseGrant(i: int): bool = all {
    grants.contains(i), grants' = grants.exclude(Set(i)),
    granted' = granted, reqs' = reqs, journal' = journal, keepJail, keepService,
  }

  // the outbox asks again for a token it has not heard about
  action reask(i: int): bool = all {
    CENTRAL, up.get(owner(i)), journal.get(i) == WaitGrant, not(reqs.contains(i)), not(grants.contains(i)),
    reqs' = reqs.union(Set(i)),
    granted' = granted, grants' = grants, journal' = journal, keepJail, keepService,
  }

  action recvGrant(i: int): bool = all {
    grants.contains(i), up.get(owner(i)), journal.get(i) == WaitGrant,
    journal' = journal.set(i, Ready), grants' = grants.exclude(Set(i)),
    granted' = granted, reqs' = reqs, keepJail, keepService,
  }

  // mutant: no central service; each jail counts only its own emissions
  action localGrant(i: int): bool = all {
    not(CENTRAL), up.get(owner(i)), journal.get(i) == WaitGrant, tokensHeld(owner(i)) < LIMIT,
    journal' = journal.set(i, Ready),
    keepLimiter, keepJail, keepService,
  }

  // ---- sending, through pf -----------------------------------------------------------------
  action send(i: int): bool = {
    val q = { i: i, attempt: attempts.get(i), src: "outbox" }
    all {
      up.get(owner(i)), journal.get(i) == Ready,
      journal' = journal.set(i, Sent),
      attempts' = attempts.setBy(i, x => x + 1),
      inflight' = inflight.union(Set(q)), egress' = egress.union(Set(q)),
      applied' = applied, answers' = answers,
      snap' = snap, up' = up, reconciled' = reconciled, rolledBack' = rolledBack, keepLimiter,
    }
  }

  // mutant: a tool jail reaches the service directly
  action toolSend(i: int): bool = {
    val q = { i: i, attempt: -1, src: "tool" }
    all {
      PF_OPEN, not(egress.contains(q)),
      inflight' = inflight.union(Set(q)), egress' = egress.union(Set(q)),
      applied' = applied, answers' = answers,
      journal' = journal, keepJail, keepLimiter,
    }
  }

  // ---- the service ---------------------------------------------------------------------------
  action apply(q: Req, answered: bool): bool = all {
    inflight.contains(q),
    inflight' = inflight.exclude(Set(q)),
    applied' = applied.setBy(q.i, x => x + 1),
    answers' = if (answered and q.src == "outbox") answers.union(Set(q.i)) else answers,
    egress' = egress, journal' = journal, keepJail, keepLimiter,
  }

  action loseCopy(q: Req): bool = all {
    inflight.contains(q), inflight' = inflight.exclude(Set(q)),
    applied' = applied, answers' = answers, egress' = egress,
    journal' = journal, keepJail, keepLimiter,
  }

  action loseAnswer(i: int): bool = all {
    answers.contains(i), answers' = answers.exclude(Set(i)),
    inflight' = inflight, applied' = applied, egress' = egress,
    journal' = journal, keepJail, keepLimiter,
  }

  action recvAnswer(i: int): bool = all {
    answers.contains(i), up.get(owner(i)), journal.get(i) == Sent,
    journal' = journal.set(i, Done), answers' = answers.exclude(Set(i)),
    inflight' = inflight, applied' = applied, egress' = egress,
    keepJail, keepLimiter,
  }

  // ---- crashes, restarts and reconcile -------------------------------------------------------
  action crash(a: int): bool = all {
    up.get(a),
    up' = up.set(a, false),
    journal' = journal, snap' = snap, attempts' = attempts, reconciled' = reconciled, rolledBack' = rolledBack,
    keepLimiter, keepService,
  }

  // The listener for answers died with the jail: what was Sent is Unknown.
  // Mutant: it is simply sent again.
  action restart(a: int): bool = all {
    not(up.get(a)),
    up' = up.set(a, true),
    journal' = IDS.mapBy(i =>
      if (owner(i) == a and journal.get(i) == Sent) (if (RECONCILE) Unknown else Ready)
      else journal.get(i)),
    snap' = snap, attempts' = attempts, reconciled' = reconciled, rolledBack' = rolledBack,
    keepLimiter, keepService,
  }

  // Read the effect once no copy can still land; it either happened (Done)
  // or never will, and the token is still held (Ready).
  action reconcile(i: int): bool = all {
    up.get(owner(i)), journal.get(i) == Unknown, not(copyOut(i)),
    journal' = journal.set(i, if (applied.get(i) > 0) Done else Ready),
    reconciled' = reconciled.union(Set((i, applied.get(i) > 0))),
    snap' = snap, up' = up, attempts' = attempts, rolledBack' = rolledBack,
    keepLimiter, keepService,
  }

  // ---- step snapshots --------------------------------------------------------------------------
  action snapshot(a: int): bool = all {
    snap' = IDS.mapBy(i => if (owner(i) == a) journal.get(i) else snap.get(i)),
    journal' = journal, up' = up, attempts' = attempts, reconciled' = reconciled, rolledBack' = rolledBack,
    keepLimiter, keepService,
  }

  // The agent's VM and datasets go back to the last snapshot; the guest will
  // ask for the same emissions again. The journal goes back with them only
  // if it lives on a snapshotted dataset.
  action rollback(a: int): bool = all {
    journal' = if (JOURNAL_SNAPSHOTTED) IDS.mapBy(i => if (owner(i) == a) snap.get(i) else journal.get(i)) else journal,
    rolledBack' = rolledBack.union(mine(a).filter(i => (journal.get(i) == Sent or journal.get(i) == Done) and snap.get(i) == New)),
    snap' = snap, up' = up, attempts' = attempts, reconciled' = reconciled,
    keepLimiter, keepService,
  }

  action step = any {
    nondet i = oneOf(IDS)
    nondet a = oneOf(AGENTS)
    any {
      issue(i), grant(i), loseReq(i), loseGrant(i), reask(i), recvGrant(i), localGrant(i),
      send(i), toolSend(i), loseAnswer(i), recvAnswer(i), reconcile(i),
      crash(a), restart(a), snapshot(a), rollback(a),
    },
    nondet q = oneOf(inflight)
    nondet answered = oneOf(Set(true, false))
    any { apply(q, answered), loseCopy(q) },
  }

  // ---- properties ------------------------------------------------------------------------------
  val atMostOnce: bool = IDS.forall(i => applied.get(i) <= 1)
  val withinLimit: bool = IDS.filter(i => applied.get(i) > 0).size() <= LIMIT
  val onlyOutboxEgress: bool = egress.forall(q => q.src == "outbox")
  val receiptsTrue: bool = IDS.forall(i => journal.get(i) == Done implies applied.get(i) > 0)
  val safety: bool = atMostOnce and withinLimit and onlyOutboxEgress and receiptsTrue

  // witnesses
  val someUnknown: bool = IDS.exists(i => journal.get(i) == Unknown)
  val limitFull: bool = granted.size() == LIMIT and IDS.exists(i => journal.get(i) == WaitGrant)
  val reconciledApplied: bool = reconciled.exists(r => r._2)
  val reconciledNotApplied: bool = reconciled.exists(r => not(r._2))
  val rolledBackAfterSend: bool = rolledBack.size() > 0
}

module design {
  import outbox(AGENTS = Set(1, 2), IDS = Set(10, 11, 20, 21), LIMIT = 2,
                JOURNAL_SNAPSHOTTED = false, CENTRAL = true, PF_OPEN = false, RECONCILE = true).* from "./outbox"
}

module journalInSnapshot {
  import outbox(AGENTS = Set(1, 2), IDS = Set(10, 11, 20, 21), LIMIT = 2,
                JOURNAL_SNAPSHOTTED = true, CENTRAL = true, PF_OPEN = false, RECONCILE = true).* from "./outbox"
}

module localLimits {
  import outbox(AGENTS = Set(1, 2), IDS = Set(10, 11, 20, 21), LIMIT = 2,
                JOURNAL_SNAPSHOTTED = false, CENTRAL = false, PF_OPEN = false, RECONCILE = true).* from "./outbox"
}

module pfOpen {
  import outbox(AGENTS = Set(1, 2), IDS = Set(10, 11, 20, 21), LIMIT = 2,
                JOURNAL_SNAPSHOTTED = false, CENTRAL = true, PF_OPEN = true, RECONCILE = true).* from "./outbox"
}

module resendOnRestart {
  import outbox(AGENTS = Set(1, 2), IDS = Set(10, 11, 20, 21), LIMIT = 2,
                JOURNAL_SNAPSHOTTED = false, CENTRAL = true, PF_OPEN = false, RECONCILE = false).* from "./outbox"
}
