// -*- mode: Bluespec; -*-
/**
 * cutover: moving a running instance to an admitted revision
 * (docs/cordis/rfc/0006-turn-runtime.md, decision 6: 6C shadow soak, 6B
 * rolling cutover at commit boundaries, durable begin and end records).
 *
 * Two assets: A, and B, which reads A. Each has a fiber that may be busy
 * with an intent issued under its asset's current revision, or Uncertain
 * after losing its receipt. A candidate revision 1 is first run in a
 * shadow: an instance whose intents are answered from a cassette and never
 * released. The soak passes or fails. A failed candidate is rejected and
 * the live instance is untouched. A passing one is admitted: a durable
 * cutover-begin record, then each asset switches to revision 1 at a commit
 * boundary (its fiber idle: no intent in flight, nothing Uncertain),
 * providers before their consumers, the old fiber retired before the new
 * one is inserted; then a durable cutover-end record. A crash keeps what is
 * durable: a recovered instance with a begin and no end finishes the
 * cutover.
 *
 * Properties:
 *  - singleWriter: no asset has two fibers writing it at once;
 *  - answeredByIssuer: a receipt is applied under the revision its intent
 *    was issued under;
 *  - noShadowEmission: no shadow intent is released;
 *  - rejectedLeavesLive: a rejected candidate leaves every asset on
 *    revision 0;
 *  - noUnadmittedMix: two revisions run side by side only between a
 *    durable begin and its end;
 *  - ordered: B never runs a newer revision than A.
 * Mutants:
 *  - RETIRE_AWAITING (`retireAwaiting`): switch an asset whose fiber still
 *    awaits a receipt -> answeredByIssuer;
 *  - SHADOW_RELEASES (`shadowReleases`) -> noShadowEmission;
 *  - VOLATILE (`volatileCutover`): no begin and end records -> after a
 *    crash, noUnadmittedMix;
 *  - CONSUMER_FIRST (`insertConsumerFirst`) -> ordered;
 *  - INSERT_FIRST (`insertFirst`): the new fiber is inserted before the old
 *    retires -> singleWriter.
 * Witnesses (checked as not(w), expected VIOLATED): cutoverWaitedOnUncertain,
 * crashMidCutoverCompleted, soakRejected, cutoverDone.
 */
module cutover {
  const RETIRE_AWAITING: bool
  const SHADOW_RELEASES: bool
  const VOLATILE: bool
  const CONSUMER_FIRST: bool
  const INSERT_FIRST: bool

  pure val ASSETS = Set("A", "B")
  pure val NONE = -1

  type Fiber = { busy: int, uncertain: bool }   // busy: the revision its intent was issued under, or NONE
  pure val idle: Fiber = { busy: NONE, uncertain: false }

  type Phase = Live | Soaking | Rejected | Cutting | Done

  type S = {
    rev: str -> int,            // each asset's revision (memory)
    durRev: str -> int,         // each asset's revision as its durable records say
    fiber: str -> Fiber,
    writers: str -> Set[int],   // revisions with a fiber writing the asset
    phase: Phase,
    begun: bool,                // a durable cutover-begin record
    ended: bool,                // a durable cutover-end record
    shadowEmitted: int,         // shadow intents released
    misanswered: int,           // receipts applied under another revision than their issuer
    crashedMid: bool, waitedUncertain: bool,
  }

  var s: S

  action init = s' = {
    rev: ASSETS.mapBy(_ => 0), durRev: ASSETS.mapBy(_ => 0), fiber: ASSETS.mapBy(_ => idle),
    writers: ASSETS.mapBy(_ => Set(0)), phase: Live, begun: false, ended: false,
    shadowEmitted: 0, misanswered: 0, crashedMid: false, waitedUncertain: false,
  }

  // ---- the live instance's work -------------------------------------------------------------
  action work: bool = {
    nondet a = ASSETS.oneOf()
    all { s.fiber.get(a) == idle, s' = { ...s, fiber: s.fiber.set(a, { busy: s.rev.get(a), uncertain: false }) } }
  }

  action receipt: bool = {
    nondet a = ASSETS.oneOf()
    val f = s.fiber.get(a)
    all {
      f.busy != NONE, not(f.uncertain),
      s' = { ...s, fiber: s.fiber.set(a, idle), misanswered: if (f.busy != s.rev.get(a)) s.misanswered + 1 else s.misanswered },
    }
  }

  action lose: bool = {
    nondet a = ASSETS.oneOf()
    val f = s.fiber.get(a)
    all { f.busy != NONE, not(f.uncertain), s' = { ...s, fiber: s.fiber.set(a, { ...f, uncertain: true }) } }
  }

  action reconcile: bool = {
    nondet a = ASSETS.oneOf()
    val f = s.fiber.get(a)
    all {
      f.uncertain,
      s' = { ...s, fiber: s.fiber.set(a, idle), misanswered: if (f.busy != s.rev.get(a)) s.misanswered + 1 else s.misanswered },
    }
  }

  // ---- admission ----------------------------------------------------------------------------
  // the candidate runs in a shadow: its intents are answered from a cassette
  action soak: bool = all {
    s.phase == Live,
    nondet emitted = Set(true, false).oneOf()
    s' = { ...s, phase: Soaking, shadowEmitted: if (SHADOW_RELEASES and emitted) s.shadowEmitted + 1 else s.shadowEmitted },
  }

  action reject: bool = all { s.phase == Soaking, s' = { ...s, phase: Rejected } }

  // admitted: the cutover begins, durably
  action begin: bool = all {
    s.phase == Soaking,
    s' = { ...s, phase: Cutting, begun: not(VOLATILE) },
  }

  // one asset switches, at a commit boundary, providers first
  action switch: bool = {
    nondet a = ASSETS.oneOf()
    val f = s.fiber.get(a)
    all {
      s.phase == Cutting, s.rev.get(a) == 0,
      RETIRE_AWAITING or f == idle,
      CONSUMER_FIRST or a == "A" or s.rev.get("A") == 1,
      s' = { ...s,
        rev: s.rev.set(a, 1), durRev: s.durRev.set(a, 1),
        writers: s.writers.set(a, if (INSERT_FIRST) Set(0, 1) else Set(1)),
        waitedUncertain: s.waitedUncertain or ASSETS.exists(b => s.fiber.get(b).uncertain),
      },
    }
  }

  // INSERT_FIRST's late retirement of the old fiber
  action retireOld: bool = {
    nondet a = ASSETS.oneOf()
    all { s.writers.get(a) == Set(0, 1), s' = { ...s, writers: s.writers.set(a, Set(1)) } }
  }

  action finish: bool = all {
    s.phase == Cutting, ASSETS.forall(a => s.rev.get(a) == 1),
    s' = { ...s, phase: Done, ended: not(VOLATILE) },
  }

  // a crash: memory is what is durable; fibers in flight are recovered as they were (engine.qnt)
  action crash: bool = {
    val mid = s.phase == Cutting
    s' = { ...s,
      rev: s.durRev,
      phase: if (s.begun and not(s.ended)) Cutting else if (s.ended) Done else if (s.phase == Rejected) Rejected else Live,
      crashedMid: s.crashedMid or mid,
    }
  }

  action step = any { work, receipt, lose, reconcile, soak, reject, begin, switch, retireOld, finish, crash }

  // ---- properties -------------------------------------------------------------------------
  val singleWriter: bool = ASSETS.forall(a => s.writers.get(a).size() <= 1)
  val answeredByIssuer: bool = s.misanswered == 0
  val noShadowEmission: bool = s.shadowEmitted == 0
  val rejectedLeavesLive: bool = s.phase == Rejected implies ASSETS.forall(a => s.rev.get(a) == 0)
  val noUnadmittedMix: bool = (s.rev.get("A") != s.rev.get("B")) implies (s.begun and not(s.ended))
  val ordered: bool = s.rev.get("B") <= s.rev.get("A")
  val safety: bool = singleWriter and answeredByIssuer and noShadowEmission and rejectedLeavesLive and noUnadmittedMix and ordered

  // witnesses
  val cutoverWaitedOnUncertain: bool = s.waitedUncertain
  val crashMidCutoverCompleted: bool = s.crashedMid and s.phase == Done
  val soakRejected: bool = s.phase == Rejected
  val cutoverDone: bool = s.phase == Done and ASSETS.forall(a => s.rev.get(a) == 1)
}

module design {
  import cutover(RETIRE_AWAITING = false, SHADOW_RELEASES = false, VOLATILE = false, CONSUMER_FIRST = false, INSERT_FIRST = false).* from "./cutover"
}
module retireAwaiting {
  import cutover(RETIRE_AWAITING = true, SHADOW_RELEASES = false, VOLATILE = false, CONSUMER_FIRST = false, INSERT_FIRST = false).* from "./cutover"
}
module shadowReleases {
  import cutover(RETIRE_AWAITING = false, SHADOW_RELEASES = true, VOLATILE = false, CONSUMER_FIRST = false, INSERT_FIRST = false).* from "./cutover"
}
module volatileCutover {
  import cutover(RETIRE_AWAITING = false, SHADOW_RELEASES = false, VOLATILE = true, CONSUMER_FIRST = false, INSERT_FIRST = false).* from "./cutover"
}
module insertConsumerFirst {
  import cutover(RETIRE_AWAITING = false, SHADOW_RELEASES = false, VOLATILE = false, CONSUMER_FIRST = true, INSERT_FIRST = false).* from "./cutover"
}
module insertFirst {
  import cutover(RETIRE_AWAITING = false, SHADOW_RELEASES = false, VOLATILE = false, CONSUMER_FIRST = false, INSERT_FIRST = true).* from "./cutover"
}
