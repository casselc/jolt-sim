// -*- mode: Bluespec; -*-
/**
 * routes: guarded assets, stream appends and feedback loops
 * (docs/cordis/rfc/0002-graph-language.md, "Routes and streams at run time").
 *
 * The review loop, in miniature:
 *
 *   input  request : int
 *   stream notes   : int up to CAPN
 *   asset  draft   = request + size(notes)
 *   asset  verdict = critic(draft)        Pass(d) when d == TARGET, else Fail(d)
 *   asset  shipped = gate(verdict)        d + 10
 *   route verdict { Pass(_) -> shipped; Fail(d) -> notes <~ d }
 *
 * request 2 fails once, appends, and passes; request 0 needs three appends
 * and meets the cap first; request 4 can never pass. A new request starts
 * a new episode: the stream is emptied.
 *
 * What the design does, and what it protects:
 *  - consistent: at rest, every asset is what a from-scratch evaluation of
 *    the graph gives for the current request and stream, and the guarded
 *    asset is bound exactly when its guard holds (a guard that stops
 *    holding withdraws the asset);
 *  - appendOnce: an append is keyed by the revision of the scrutinee it
 *    answers, so a revision appends at most once, however often the route
 *    is re-run (a restart, a duplicate delivery);
 *  - bounded: a stream on a feedback loop has a capacity, and a full stream
 *    refuses the append, so a loop whose critic never passes still comes to
 *    rest.
 * Mutants:
 *  - WITHDRAW = false (`keepGuarded`): a guarded asset keeps its value when
 *    its guard stops holding -> consistent;
 *  - APPEND_ONCE = false (`appendEveryRun`): the route appends whenever it
 *    runs on a failing scrutinee -> appendOnce;
 *  - CAP = false (`noCap`): an unbounded stream; request 4 loops forever ->
 *    bounded.
 * Witnesses (checked as not(w), expected VIOLATED): loopPassed (a loop
 * appended, then passed and shipped), capReachedAtRest (a loop that never
 * passes came to rest at the cap), withdrawn (a shipped value was withdrawn),
 * refusedWhenFull.
 */
module routes {
  const CAP: bool
  const APPEND_ONCE: bool
  const WITHDRAW: bool

  pure val CAPN = 2
  pure val TARGET = 3
  pure val REQUESTS: Set[int] = Set(0, 2, 4)
  pure val NONE = -1

  type Verdict = { pass: bool, payload: int }

  pure def critic(d: int): Verdict = { pass: d == TARGET, payload: d }
  pure def gate(v: Verdict): int = v.payload + 10

  var request: int
  var requestRev: int
  var notes: List[int]
  var notesRev: int
  var draft: int
  var draftRev: int
  var draftSeen: (int, int)       // (requestRev, notesRev) draft was computed from
  var verdict: Verdict
  var verdictRev: int
  var verdictSeen: int            // draftRev verdict was computed from
  var shipped: int                // NONE: not bound
  var shippedSeen: int            // verdictRev shipped answers for
  var answered: Set[int]          // verdict revisions the route has answered
  var appends: int
  var dupAppends: int             // appends for a revision already answered
  var refused: int
  var withdrawals: int

  // ---- staleness --------------------------------------------------------------------------
  def draftStale: bool = draftSeen != (requestRev, notesRev)
  def verdictStale: bool = draftStale or verdictSeen != draftRev
  def shippedPending: bool =
    not(verdictStale) and (
      (verdict.pass and shippedSeen != verdictRev)
      or (WITHDRAW and not(verdict.pass) and shipped != NONE)
    )
  def appendPending: bool = not(verdictStale) and not(verdict.pass) and not(answered.contains(verdictRev))
  def quiet: bool = not(draftStale) and not(verdictStale) and not(shippedPending) and not(appendPending)

  // ---- actions ----------------------------------------------------------------------------
  action unchangedCounters = all {
    appends' = appends, dupAppends' = dupAppends, refused' = refused, withdrawals' = withdrawals,
  }

  action init = all {
    request' = 2, requestRev' = 0, notes' = List(), notesRev' = 0,
    // the graph starts unevaluated: everything is stale
    draft' = 0, draftRev' = 0, draftSeen' = (-1, -1),
    verdict' = critic(0), verdictRev' = 0, verdictSeen' = -1,
    shipped' = NONE, shippedSeen' = -1,
    answered' = Set(), appends' = 0, dupAppends' = 0, refused' = 0, withdrawals' = 0,
  }

  // a new request starts a new episode: the stream is emptied
  action setRequest(x: int): bool = all {
    x != request,
    request' = x, requestRev' = requestRev + 1,
    notes' = List(), notesRev' = notesRev + 1,
    draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
    verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
    shipped' = shipped, shippedSeen' = shippedSeen, answered' = answered,
    unchangedCounters,
  }

  action recomputeDraft: bool = {
    val d = request + notes.length()
    all {
      draftStale,
      draft' = d, draftRev' = if (d != draft) draftRev + 1 else draftRev,
      draftSeen' = (requestRev, notesRev),
      request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
      verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
      shipped' = shipped, shippedSeen' = shippedSeen, answered' = answered,
      unchangedCounters,
    }
  }

  action recomputeVerdict: bool = {
    val v = critic(draft)
    all {
      not(draftStale), verdictSeen != draftRev,
      verdict' = v, verdictRev' = if (v != verdict) verdictRev + 1 else verdictRev,
      verdictSeen' = draftRev,
      request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
      draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
      shipped' = shipped, shippedSeen' = shippedSeen, answered' = answered,
      unchangedCounters,
    }
  }

  // the guarded asset: computed when its guard holds ...
  action recomputeShipped: bool = all {
    not(verdictStale), verdict.pass, shippedSeen != verdictRev,
    shipped' = gate(verdict), shippedSeen' = verdictRev,
    request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
    draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
    verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen, answered' = answered,
    unchangedCounters,
  }

  // ... and withdrawn when it stops holding
  action withdrawShipped: bool = all {
    WITHDRAW, not(verdictStale), not(verdict.pass), shipped != NONE,
    shipped' = NONE, shippedSeen' = verdictRev, withdrawals' = withdrawals + 1,
    request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
    draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
    verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen, answered' = answered,
    appends' = appends, dupAppends' = dupAppends, refused' = refused,
  }

  // the route's append: once per scrutinee revision (the intent's key), and
  // refused by a full stream
  action routeAppend: bool = {
    val again = answered.contains(verdictRev)
    val full = CAP and notes.length() >= CAPN
    all {
      not(verdictStale), not(verdict.pass),
      APPEND_ONCE implies not(again),
      notes' = if (full) notes else notes.append(verdict.payload),
      notesRev' = if (full) notesRev else notesRev + 1,
      answered' = answered.union(Set(verdictRev)),
      appends' = if (full) appends else appends + 1,
      dupAppends' = if (again and not(full)) dupAppends + 1 else dupAppends,
      refused' = if (full) refused + 1 else refused,
      request' = request, requestRev' = requestRev,
      draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
      verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
      shipped' = shipped, shippedSeen' = shippedSeen, withdrawals' = withdrawals,
    }
  }

  action idle = all {
    request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
    draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
    verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
    shipped' = shipped, shippedSeen' = shippedSeen, answered' = answered,
    unchangedCounters,
  }

  action step = any {
    nondet x = oneOf(REQUESTS)
    setRequest(x),
    recomputeDraft,
    recomputeVerdict,
    recomputeShipped,
    withdrawShipped,
    routeAppend,
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val consistent: bool = quiet implies all {
    draft == request + notes.length(),
    verdict == critic(draft),
    if (verdict.pass) shipped == gate(verdict) else shipped == NONE,
  }
  val appendOnce: bool = dupAppends == 0
  val bounded: bool = notes.length() <= CAPN
  val safety: bool = consistent and appendOnce and bounded

  // witnesses
  val loopPassed: bool = quiet and appends > 0 and shipped != NONE
  val capReachedAtRest: bool = quiet and notes.length() == CAPN and not(verdict.pass)
  val withdrawn: bool = withdrawals > 0
  val refusedWhenFull: bool = refused > 0
}

module design {
  import routes(CAP = true, APPEND_ONCE = true, WITHDRAW = true).* from "./routes"
}

module keepGuarded {
  import routes(CAP = true, APPEND_ONCE = true, WITHDRAW = false).* from "./routes"
}

module appendEveryRun {
  import routes(CAP = true, APPEND_ONCE = false, WITHDRAW = true).* from "./routes"
}

module noCap {
  import routes(CAP = false, APPEND_ONCE = true, WITHDRAW = true).* from "./routes"
}
