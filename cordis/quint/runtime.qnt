// -*- mode: Bluespec; -*-
/**
 * runtime: the engine's protocol, as its traces show it
 * (docs/cordis/rfc/0006-turn-runtime.md, revision 2, stage 1's
 * conformance; cordis/engine).
 *
 * engine.qnt and observe.qnt check the commit protocol and observational
 * retries over a fixed, small world. This model states the same rules over
 * the engine's own alphabet, with parameters, so that a run of the engine
 * can be replayed in it step by step (quintgo validate): every record the
 * engine appends, every sync, release, reconcile request and crash is one
 * action here, and a run conforms when every step is allowed.
 *
 * State: the last record appended (seq) and the last durable one; what
 * each asset's fiber awaits (an intent key, its attempt, and whether it is
 * Uncertain), in memory, and the history of those changes by record, from
 * which a crash rebuilds memory.
 *
 * The rules the guards carry:
 *  - the output gate (engine.qnt's releasedOnlyDurable): an intent is
 *    released only while a fiber awaits it and the record that made it
 *    await is durable;
 *  - receipts by key and attempt (engine.qnt's receiptOnce, observe.qnt's
 *    applyLate): a receipt resumes only the fiber awaiting exactly that key
 *    and attempt;
 *  - observational and effectful (observe.qnt): only an observational kind
 *    is attempted again after a loss, up to MAX attempts, then given up;
 *    only an effectful one becomes Uncertain, and a reconcile is asked only
 *    for an Uncertain fiber; a reconcile that finds no effect sends the
 *    next attempt;
 *  - recovery (engine.qnt's crash): memory is the durable state.
 * Mutants, each a design whose engine would break one rule; replaying the
 * design's own witnesses shows the rule is reachable:
 *  - EARLY (`releaseEarly`): release from a record not yet durable ->
 *    gateHeld;
 *  - ANY_ATTEMPT (`anyAttempt`): a receipt for another attempt of the key
 *    resumes -> attemptMatched.
 * Witnesses: retried, doubted, refetched, recoveredAwaiting, gaveUp.
 */
module runtime {
  const ASSETS: Set[str]
  const MAX: int
  const EARLY: bool
  const ANY_ATTEMPT: bool

  type Await = { key: str, attempt: int, uncertain: bool }
  pure val NONE: Await = { key: "", attempt: 0, uncertain: false }

  type Change = { seq: int, asset: str, await: Await }

  var seq: int
  var durable: int
  var awaiting: str -> Await
  var history: List[Change]
  // ghosts for the properties and witnesses
  var released: Set[(str, int, int, int)]   // (key, attempt, the record that made it awaited, durable at release)
  var resumed: Set[(str, int, int)]    // (key, attempt, the attempt awaited when it resumed)
  var counts: str -> int

  def bump(c: str): str -> int = counts.set(c, counts.get(c) + 1)

  // the record that made [a] await what it awaits, if any
  def madeAt(a: str): int =
    history.foldl(-1, (m, c) => if (c.asset == a and c.await == awaiting.get(a)) c.seq else m)

  action init = all {
    seq' = 0, durable' = 0,
    awaiting' = ASSETS.mapBy(_ => NONE),
    history' = List(), released' = Set(), resumed' = Set(),
    counts' = Set("retry", "doubt", "refetch", "recover", "giveUp").mapBy(_ => 0),
  }

  action keepGhosts = all { released' = released, resumed' = resumed, counts' = counts }

  // a record that changes what one asset awaits
  action change(a: str, w: Await): bool = all {
    seq' = seq + 1, durable' = durable,
    awaiting' = awaiting.set(a, w),
    history' = history.append({ seq: seq + 1, asset: a, await: w }),
  }

  // a record that changes nothing awaited (a computation begun or finished,
  // a withdrawal, an append, an input)
  action persist: bool = all {
    seq' = seq + 1, durable' = durable, awaiting' = awaiting, history' = history, keepGhosts,
  }

  // a turn ends in an intent: the fiber awaits it
  action emit(asset: str, key: str, attempt: int): bool = all {
    ASSETS.contains(asset), awaiting.get(asset) == NONE, attempt == 1, key != "",
    change(asset, { key: key, attempt: attempt, uncertain: false }), keepGhosts,
  }

  // an observational intent's receipt was lost: the next attempt, same key
  action retry(asset: str, key: str, attempt: int): bool = all {
    awaiting.get(asset) == { key: key, attempt: attempt - 1, uncertain: false }, attempt <= MAX,
    change(asset, { key: key, attempt: attempt, uncertain: false }),
    released' = released, resumed' = resumed, counts' = bump("retry"),
  }

  // every attempt lost: the computation fails
  action giveUp(asset: str, key: str, attempt: int): bool = all {
    awaiting.get(asset) == { key: key, attempt: attempt, uncertain: false }, attempt == MAX,
    change(asset, NONE),
    released' = released, resumed' = resumed, counts' = bump("giveUp"),
  }

  // an effectful intent's receipt was lost: Uncertain
  action doubt(asset: str, key: str, attempt: int): bool = all {
    awaiting.get(asset) == { key: key, attempt: attempt, uncertain: false },
    change(asset, { key: key, attempt: attempt, uncertain: true }),
    released' = released, resumed' = resumed, counts' = bump("doubt"),
  }

  // a reconcile found no effect: send the next attempt
  action refetch(asset: str, key: str, attempt: int): bool = all {
    awaiting.get(asset) == { key: key, attempt: attempt - 1, uncertain: true },
    change(asset, { key: key, attempt: attempt, uncertain: false }),
    released' = released, resumed' = resumed, counts' = bump("refetch"),
  }

  // a receipt (or a reconcile that found the effect) resumes the fiber
  action resume(asset: str, key: str, attempt: int): bool = {
    val w = awaiting.get(asset)
    all {
      w.key == key, w.key != "", ANY_ATTEMPT or w.attempt == attempt,
      change(asset, NONE),
      released' = released, resumed' = resumed.union(Set((key, attempt, w.attempt))), counts' = counts,
    }
  }

  // every appended record becomes durable
  action sync: bool = all {
    seq' = seq, durable' = seq, awaiting' = awaiting, history' = history, keepGhosts,
  }

  // the output gate: only an awaited intent whose record is durable
  action release(key: str, attempt: int): bool = {
    val holders = ASSETS.filter(a => awaiting.get(a) == { key: key, attempt: attempt, uncertain: false })
    val made = holders.fold(seq + 1, (m, a) => if (madeAt(a) < m) madeAt(a) else m)
    all {
      holders != Set(), EARLY or made <= durable,
      seq' = seq, durable' = durable, awaiting' = awaiting, history' = history,
      released' = released.union(Set((key, attempt, made, durable))), resumed' = resumed, counts' = counts,
    }
  }

  // a reconcile is asked only for an Uncertain fiber
  action ask(key: str, attempt: int): bool = all {
    ASSETS.exists(a => awaiting.get(a) == { key: key, attempt: attempt, uncertain: true }),
    seq' = seq, durable' = durable, awaiting' = awaiting, history' = history, keepGhosts,
  }

  // a crash: memory is what the durable records say
  action crash: bool = {
    val kept = history.select(c => c.seq <= durable)
    all {
      seq' = durable, durable' = durable,
      awaiting' = ASSETS.mapBy(a => kept.foldl(NONE, (w, c) => if (c.asset == a) c.await else w)),
      history' = kept,
      released' = released, resumed' = resumed,
      counts' = if (ASSETS.exists(a => awaiting.get(a) != NONE)) bump("recover") else counts,
    }
  }

  // a step of the world (the outbox sending, a receipt nobody awaits) that changes nothing here
  action outside: bool = all {
    seq' = seq, durable' = durable, awaiting' = awaiting, history' = history, keepGhosts,
  }

  action step = any {
    persist, sync, crash,
    nondet a = ASSETS.oneOf()
    nondet k = Set("k1", "k2").oneOf()
    nondet n = 1.to(MAX).oneOf()
    any { emit(a, k, n), retry(a, k, n), giveUp(a, k, n), doubt(a, k, n), refetch(a, k, n), resume(a, k, n), release(k, n), ask(k, n) },
  }

  // ---- properties -------------------------------------------------------------------------
  // every release happened with its record durable (checked against the record's position)
  val gateHeld: bool = released.forall(r => r._3 <= r._4)
  val attemptMatched: bool = resumed.forall(r => r._2 == r._3)
  val safety: bool = gateHeld and attemptMatched

  // witnesses
  val retried: bool = counts.get("retry") > 0
  val doubted: bool = counts.get("doubt") > 0
  val refetched: bool = counts.get("refetch") > 0
  val recoveredAwaiting: bool = counts.get("recover") > 0
  val gaveUp: bool = counts.get("giveUp") > 0
}

module design {
  import runtime(ASSETS = Set("a", "b"), MAX = 3, EARLY = false, ANY_ATTEMPT = false).* from "./runtime"
}
module releaseEarly {
  import runtime(ASSETS = Set("a", "b"), MAX = 3, EARLY = true, ANY_ATTEMPT = false).* from "./runtime"
}
module anyAttempt {
  import runtime(ASSETS = Set("a", "b"), MAX = 3, EARLY = false, ANY_ATTEMPT = true).* from "./runtime"
}
// the review loop's instance, which the engine's traces are replayed in
module reviewLoop {
  import runtime(ASSETS = Set("draft", "verdict", "shipped"), MAX = 3, EARLY = false, ANY_ATTEMPT = false).* from "./runtime"
}
