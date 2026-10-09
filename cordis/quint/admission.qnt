// -*- mode: Bluespec; -*-
/**
 * admission: agents patching a live graph concurrently (docs/cordis/rfc/
 * 0002-graph-language.md, "Storage and edits"; build order item 5).
 *
 * Each agent reads the live revision, writes a structural patch against it
 * (its base), and submits it. Admission runs the gates (parse, type-check,
 * cost, Cedar Analysis, contracts, model check, soak; abstracted here to
 * pass or fail) and makes the patch live only if its base is still the live
 * revision. A patch that narrows a signature, widens effects or changes a
 * variant also needs an approval, given for that exact revision. A live
 * revision can be rolled back to its parent.
 *
 * Properties:
 *  - liveAdmitted: the live revision always passed the gates;
 *  - noLostUpdate: no two kept revisions share a parent, so history is a
 *    line and no agent's change is silently overwritten by another's;
 *  - narrowingApproved: a narrowing revision is live only with an approval
 *    of that revision.
 * Mutants:
 *  - CHECK_BASE = false (`lastWriterWins`): a patch is admitted whatever its
 *    base -> noLostUpdate;
 *  - APPROVE_BY_AGENT (`approvalByAgent`): an approval is recorded for the
 *    agent, not the revision, so it carries over to the agent's nextRev
 *    narrowing patch -> narrowingApproved.
 * Witnesses (checked as not(w), expected VIOLATED): staleRefused,
 * rolledBackThenAdmitted, narrowingLive.
 */
module admission {
  type Patch = { rev: int, agent: int, base: int, narrow: bool }

  const AGENTS: Set[int]
  const MAX_REVS: int
  const CHECK_BASE: bool
  const APPROVE_BY_AGENT: bool

  var live: int
  var nextRev: int
  var parent: int -> int           // revision -> the base it was written against
  var narrows: int -> bool
  var admitted: Set[int]           // revisions that went live at some point
  var rolledBack: Set[int]
  var pending: Set[Patch]
  var seen: int -> int             // agent -> the revision it last read
  var approvedRevs: Set[int]
  var approvedAgents: Set[int]
  var refusedStale: bool

  action init = all {
    live' = 0, nextRev' = 1, parent' = Map(0 -> -1), narrows' = Map(0 -> false),
    admitted' = Set(0), rolledBack' = Set(), pending' = Set(),
    seen' = AGENTS.mapBy(_ => 0), approvedRevs' = Set(), approvedAgents' = Set(),
    refusedStale' = false,
  }

  action read(a: int): bool = all {
    seen' = seen.set(a, live),
    live' = live, nextRev' = nextRev, parent' = parent, narrows' = narrows, admitted' = admitted,
    rolledBack' = rolledBack, pending' = pending, approvedRevs' = approvedRevs,
    approvedAgents' = approvedAgents, refusedStale' = refusedStale,
  }

  action propose(a: int, narrow: bool): bool = all {
    nextRev < MAX_REVS,
    pending' = pending.union(Set({ rev: nextRev, agent: a, base: seen.get(a), narrow: narrow })),
    parent' = parent.put(nextRev, seen.get(a)), narrows' = narrows.put(nextRev, narrow),
    nextRev' = nextRev + 1,
    live' = live, admitted' = admitted, rolledBack' = rolledBack, seen' = seen,
    approvedRevs' = approvedRevs, approvedAgents' = approvedAgents, refusedStale' = refusedStale,
  }

  action approve(p: Patch): bool = all {
    pending.contains(p), p.narrow,
    approvedRevs' = approvedRevs.union(Set(p.rev)),
    approvedAgents' = approvedAgents.union(Set(p.agent)),
    live' = live, nextRev' = nextRev, parent' = parent, narrows' = narrows, admitted' = admitted,
    rolledBack' = rolledBack, pending' = pending, seen' = seen, refusedStale' = refusedStale,
  }

  def approvedFor(p: Patch): bool =
    if (APPROVE_BY_AGENT) approvedAgents.contains(p.agent) else approvedRevs.contains(p.rev)

  // The gates ran; gatesPass is their verdict.
  action admit(p: Patch, gatesPass: bool): bool = {
    val stale = CHECK_BASE and p.base != live
    val ok = gatesPass and not(stale) and (not(p.narrow) or approvedFor(p))
    all {
      pending.contains(p),
      // a narrowing patch waits for its approval rather than being refused
      not(p.narrow) or approvedFor(p) or not(gatesPass) or stale,
      pending' = pending.exclude(Set(p)),
      live' = if (ok) p.rev else live,
      admitted' = if (ok) admitted.union(Set(p.rev)) else admitted,
      refusedStale' = (refusedStale or (gatesPass and stale)),
      approvedAgents' = approvedAgents,
      nextRev' = nextRev, parent' = parent, narrows' = narrows, rolledBack' = rolledBack,
      seen' = seen, approvedRevs' = approvedRevs,
    }
  }

  action rollback = all {
    live != 0,
    live' = parent.get(live),
    rolledBack' = rolledBack.union(Set(live)),
    nextRev' = nextRev, parent' = parent, narrows' = narrows, admitted' = admitted, pending' = pending,
    seen' = seen, approvedRevs' = approvedRevs, approvedAgents' = approvedAgents,
    refusedStale' = refusedStale,
  }

  action idle = all {
    live' = live, nextRev' = nextRev, parent' = parent, narrows' = narrows, admitted' = admitted,
    rolledBack' = rolledBack, pending' = pending, seen' = seen, approvedRevs' = approvedRevs,
    approvedAgents' = approvedAgents, refusedStale' = refusedStale,
  }

  action step = any {
    nondet a = oneOf(AGENTS)
    nondet narrow = oneOf(Set(true, false))
    any { read(a), propose(a, narrow) },
    nondet p = oneOf(pending)
    nondet gatesPass = oneOf(Set(true, false))
    any { approve(p), admit(p, gatesPass) },
    rollback,
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val kept: Set[int] = admitted.exclude(rolledBack)
  val liveAdmitted: bool = admitted.contains(live)
  val noLostUpdate: bool = kept.forall(r1 => kept.forall(r2 =>
    (r1 != r2 and r1 != 0 and r2 != 0) implies parent.get(r1) != parent.get(r2)))
  val narrowingApproved: bool = admitted.forall(r => narrows.get(r) implies approvedRevs.contains(r))
  val safety: bool = liveAdmitted and noLostUpdate and narrowingApproved

  // witnesses
  val staleRefused: bool = refusedStale
  val rolledBackThenAdmitted: bool = rolledBack.exists(r => admitted.exists(s => s > r and parent.get(s) == parent.get(r)))
  val narrowingLive: bool = narrows.get(live)
}

module design {
  import admission(AGENTS = Set(1, 2), MAX_REVS = 6, CHECK_BASE = true, APPROVE_BY_AGENT = false).* from "./admission"
}

module lastWriterWins {
  import admission(AGENTS = Set(1, 2), MAX_REVS = 6, CHECK_BASE = false, APPROVE_BY_AGENT = false).* from "./admission"
}

module approvalByAgent {
  import admission(AGENTS = Set(1, 2), MAX_REVS = 6, CHECK_BASE = true, APPROVE_BY_AGENT = true).* from "./admission"
}
