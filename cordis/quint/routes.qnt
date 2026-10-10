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
 *   stream log     : int up to CAPL
 *   route verdict { Pass(d) -> shipped, log <~ d; Fail(d) -> notes <~ d }
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
 *  - appendOnce: an append is keyed by the computation of the scrutinee it
 *    answers (the episode, and which computation of the scrutinee in it),
 *    so a computation appends at most once, however often the route is
 *    re-run (a restart, a duplicate delivery);
 *  - noStall: a loop whose critic fails comes to rest only at its cap. A
 *    critic may give the same verdict for a new draft (failing verdicts
 *    here carry a constant payload, as an LLM repeats itself), and a new
 *    request may give a draft equal to an earlier one: neither may stop
 *    the loop. Keying by the scrutinee's value revision, as RFC 0002
 *    revision 2 first said, does (the engine's review loop found it);
 *  - bounded: a stream on a feedback loop has a capacity, and a full stream
 *    refuses the append, so a loop whose critic never passes still comes to
 *    rest.
 * Mutants:
 *  - WITHDRAW = false (`keepGuarded`): a guarded asset keeps its value when
 *    its guard stops holding -> consistent;
 *  - APPEND_ONCE = false (`appendEveryRun`): the route appends whenever it
 *    runs on a failing scrutinee -> appendOnce;
 *  - CAP = false (`noCap`): an unbounded stream; request 4 loops forever ->
 *    bounded;
 *  - KEY_BY_VALUE (`keyByValue`): appends keyed by the scrutinee's value
 *    revision -> noStall;
 *  - ANSWERED_IN_MEMORY (`answeredInMemory`): the answered keys are not
 *    durable, so a crash between an append and the next recompute lets
 *    the recovered route append to the log again -> appendOnce. (An append
 *    to notes is safe without them: it makes the scrutinee stale, and its
 *    recompute is a new computation with a new key. The log, which the
 *    scrutinee does not read, has only the key.)
 * A crash and recovery may strike between any two steps (RFC 0006,
 * "Extensions to existing models"): the records keep the request, the
 * stream, the assets, the episode and computation counts, and the answered
 * keys, which are written in the record of the append they answer.
 * Witnesses (checked as not(w), expected VIOLATED): loopPassed (a loop
 * appended, then passed and shipped), capReachedAtRest (a loop that never
 * passes came to rest at the cap), withdrawn (a shipped value was withdrawn),
 * refusedWhenFull, crashedBetween (a crash struck after a log append whose
 * computation is still current).
 */
module routes {
  const CAP: bool
  const APPEND_ONCE: bool
  const WITHDRAW: bool
  const KEY_BY_VALUE: bool
  const ANSWERED_IN_MEMORY: bool

  pure val CAPN = 2
  pure val TARGET = 3
  pure val REQUESTS: Set[int] = Set(0, 2, 4)
  pure val NONE = -1
  pure val MAX_CRASHES = 2
  pure val CAPL = 3

  type Verdict = { pass: bool, payload: int }

  // a failing verdict's payload is the same every time: the critic repeats itself
  pure def critic(d: int): Verdict = { pass: d == TARGET, payload: if (d == TARGET) d else 0 }
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
  var answered: Set[int]          // append keys the route has answered
  var episode: int                // requests so far
  var verdictRuns: int            // computations of verdict
  var appends: int
  var dupAppends: int             // appends for a revision already answered
  var refused: int
  var withdrawals: int
  var log: List[int]               // a stream the route appends to that the scrutinee does not read
  var crashes: int
  var everAnswered: Set[int]       // ghost: every append key answered, across crashes
  var crashedAfterAppend: bool     // ghost: a crash struck between an append and the next recompute

  // ---- staleness --------------------------------------------------------------------------
  def draftStale: bool = draftSeen != (requestRev, notesRev)
  def verdictStale: bool = draftStale or verdictSeen != draftRev
  def shippedPending: bool =
    not(verdictStale) and (
      (verdict.pass and shippedSeen != verdictRev)
      or (WITHDRAW and not(verdict.pass) and shipped != NONE)
    )
  def akey: int = if (KEY_BY_VALUE) verdictRev else episode * 1000 + verdictRuns
  def appendPending: bool = not(verdictStale) and not(verdict.pass) and not(answered.contains(akey))
  // the log append's key: the same computation, another append
  def lkey: int = akey + 500
  def logPending: bool = not(verdictStale) and verdict.pass and not(answered.contains(lkey))
  def quiet: bool = not(draftStale) and not(verdictStale) and not(shippedPending) and not(appendPending) and not(logPending)

  // ---- actions ----------------------------------------------------------------------------
  action unchangedRest = all {
    log' = log,
    crashes' = crashes, everAnswered' = everAnswered, crashedAfterAppend' = crashedAfterAppend,
  }
  action unchangedCounters = all {
    appends' = appends, dupAppends' = dupAppends, refused' = refused, withdrawals' = withdrawals,
    unchangedRest,
  }

  action init = all {
    request' = 2, requestRev' = 0, notes' = List(), notesRev' = 0,
    // the graph starts unevaluated: everything is stale
    draft' = 0, draftRev' = 0, draftSeen' = (-1, -1),
    verdict' = critic(0), verdictRev' = 0, verdictSeen' = -1,
    shipped' = NONE, shippedSeen' = -1,
    answered' = Set(), appends' = 0, dupAppends' = 0, refused' = 0, withdrawals' = 0,
    episode' = 0, verdictRuns' = 0,
    log' = List(), crashes' = 0, everAnswered' = Set(), crashedAfterAppend' = false,
  }

  // a new request starts a new episode: the stream is emptied
  action setRequest(x: int): bool = all {
    episode' = episode + 1, verdictRuns' = verdictRuns,
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
      episode' = episode, verdictRuns' = verdictRuns,
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
      episode' = episode, verdictRuns' = verdictRuns + 1,
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
    episode' = episode, verdictRuns' = verdictRuns,
    not(verdictStale), verdict.pass, shippedSeen != verdictRev,
    shipped' = gate(verdict), shippedSeen' = verdictRev,
    request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
    draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
    verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen, answered' = answered,
    unchangedCounters,
  }

  // ... and withdrawn when it stops holding
  action withdrawShipped: bool = all {
    episode' = episode, verdictRuns' = verdictRuns,
    WITHDRAW, not(verdictStale), not(verdict.pass), shipped != NONE,
    shipped' = NONE, shippedSeen' = verdictRev, withdrawals' = withdrawals + 1,
    request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
    draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
    verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen, answered' = answered,
    appends' = appends, dupAppends' = dupAppends, refused' = refused,
    unchangedRest,
  }

  // the route's append: once per scrutinee revision (the intent's key), and
  // refused by a full stream
  action routeAppend: bool = {
    val again = answered.contains(akey)
    val dup = everAnswered.contains(akey)
    val full = CAP and notes.length() >= CAPN
    all {
      episode' = episode, verdictRuns' = verdictRuns,
      not(verdictStale), not(verdict.pass),
      APPEND_ONCE implies not(again),
      notes' = if (full) notes else notes.append(verdict.payload),
      notesRev' = if (full) notesRev else notesRev + 1,
      answered' = answered.union(Set(akey)),
      appends' = if (full) appends else appends + 1,
      dupAppends' = if (dup and not(full)) dupAppends + 1 else dupAppends,
      everAnswered' = everAnswered.union(Set(akey)),
      crashes' = crashes, crashedAfterAppend' = crashedAfterAppend, log' = log,
      refused' = if (full) refused + 1 else refused,
      request' = request, requestRev' = requestRev,
      draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
      verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
      shipped' = shipped, shippedSeen' = shippedSeen, withdrawals' = withdrawals,
    }
  }

  // a replay of a route run already answered (a restart, a duplicate
  // delivery): the design recognises its key and does nothing; a route that
  // appends whenever it runs appends again
  action replayRoute: bool = {
    val full = CAP and notes.length() >= CAPN
    all {
      not(APPEND_ONCE), answered.contains(akey), not(verdict.pass), not(full),
      notes' = notes.append(verdict.payload), notesRev' = notesRev + 1,
      appends' = appends + 1, dupAppends' = dupAppends + 1,
      answered' = answered, refused' = refused, withdrawals' = withdrawals,
      request' = request, requestRev' = requestRev,
      draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
      verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
      shipped' = shipped, shippedSeen' = shippedSeen,
      episode' = episode, verdictRuns' = verdictRuns,
      unchangedRest,
    }
  }

  // the route's other arm appends to the log on a pass: the scrutinee does
  // not read the log, so nothing recomputes it after the append, and only
  // the answered key stops the same computation appending again
  action logAppend: bool = {
    val again = answered.contains(lkey)
    val full = log.length() >= CAPL
    all {
      not(verdictStale), verdict.pass,
      APPEND_ONCE implies not(again),
      log' = if (full) log else log.append(verdict.payload),
      answered' = answered.union(Set(lkey)),
      everAnswered' = everAnswered.union(Set(lkey)),
      appends' = if (full) appends else appends + 1,
      dupAppends' = if (everAnswered.contains(lkey) and not(full)) dupAppends + 1 else dupAppends,
      refused' = if (full) refused + 1 else refused,
      crashes' = crashes, crashedAfterAppend' = crashedAfterAppend, withdrawals' = withdrawals,
      episode' = episode, verdictRuns' = verdictRuns,
      request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
      draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
      verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
      shipped' = shipped, shippedSeen' = shippedSeen,
    }
  }

  // a crash and recovery: what the records hold survives (the request, the
  // stream, each asset with the revisions it was computed from, the episode
  // and the computation counts, and the answered keys, written with the
  // append they answer); ANSWERED_IN_MEMORY loses the answered keys, as if
  // the route kept them in memory
  action crash = all {
    crashes < MAX_CRASHES,
    crashes' = crashes + 1,
    answered' = if (ANSWERED_IN_MEMORY) Set() else answered,
    crashedAfterAppend' = (crashedAfterAppend or (not(verdictStale) and answered.contains(lkey))),
    everAnswered' = everAnswered, log' = log,
    episode' = episode, verdictRuns' = verdictRuns,
    request' = request, requestRev' = requestRev, notes' = notes, notesRev' = notesRev,
    draft' = draft, draftRev' = draftRev, draftSeen' = draftSeen,
    verdict' = verdict, verdictRev' = verdictRev, verdictSeen' = verdictSeen,
    shipped' = shipped, shippedSeen' = shippedSeen,
    appends' = appends, dupAppends' = dupAppends, refused' = refused, withdrawals' = withdrawals,
  }

  action idle = all {
    episode' = episode, verdictRuns' = verdictRuns,
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
    logAppend,
    replayRoute,
    crash,
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
  val noStall: bool = (quiet and not(verdict.pass)) implies notes.length() == CAPN
  val safety: bool = consistent and appendOnce and bounded and noStall

  // witnesses
  val loopPassed: bool = quiet and appends > 0 and shipped != NONE
  val capReachedAtRest: bool = quiet and notes.length() == CAPN and not(verdict.pass)
  val withdrawn: bool = withdrawals > 0
  val refusedWhenFull: bool = refused > 0
  val crashedBetween: bool = crashedAfterAppend
}

module design {
  import routes(CAP = true, APPEND_ONCE = true, WITHDRAW = true, KEY_BY_VALUE = false, ANSWERED_IN_MEMORY = false).* from "./routes"
}

module keepGuarded {
  import routes(CAP = true, APPEND_ONCE = true, WITHDRAW = false, KEY_BY_VALUE = false, ANSWERED_IN_MEMORY = false).* from "./routes"
}

module appendEveryRun {
  import routes(CAP = true, APPEND_ONCE = false, WITHDRAW = true, KEY_BY_VALUE = false, ANSWERED_IN_MEMORY = false).* from "./routes"
}

module noCap {
  import routes(CAP = false, APPEND_ONCE = true, WITHDRAW = true, KEY_BY_VALUE = false, ANSWERED_IN_MEMORY = false).* from "./routes"
}

module keyByValue {
  import routes(CAP = true, APPEND_ONCE = true, WITHDRAW = true, KEY_BY_VALUE = true, ANSWERED_IN_MEMORY = false).* from "./routes"
}

module answeredInMemory {
  import routes(CAP = true, APPEND_ONCE = true, WITHDRAW = true, KEY_BY_VALUE = false, ANSWERED_IN_MEMORY = true).* from "./routes"
}
