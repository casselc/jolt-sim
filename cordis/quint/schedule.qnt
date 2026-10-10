// -*- mode: Bluespec; -*-
/**
 * schedule: schedule independence and fairness
 * (docs/cordis/rfc/0006-turn-runtime.md, decision 4: 4B within a graph,
 * deficit round robin across graphs; "Models to write first").
 *
 * Two parts in one state.
 *
 * Schedule independence (Cordis's confluence, Theorem 80, extended to
 * emissions). Two copies of one instance run the same TASKS from the same
 * inputs, each in an order of its own. A task adds its weight to a shared
 * key (Add commutes; PUT_SHARED overwrites instead) and emits one intent
 * for the task, keyed by the task (POSITIONAL_KEY keys it by the copy's
 * step count instead, so the same key names different work in each copy). At rest, the copies agree.
 *
 * Fairness. GRAPHS graphs share one loop. Graph 0 always has work (a
 * graph that never rests); the others have work now and then. Each step
 * costs COST(g) cost units. Deficit round robin: the loop visits graphs in
 * turn; a visited graph with work gets QUANTUM added to its deficit and
 * runs while its deficit covers its next step; a graph without work keeps
 * no deficit. STRICT_PRIORITY instead always runs the lowest-numbered graph
 * with work.
 *
 * Properties:
 *  - sameRest: when both copies are at rest, their tables and their sets
 *    of released (key, task) intents are equal;
 *  - boundedWait: a graph with work is passed over at most BOUND picks
 *    (a safety stand-in for fairness: liveness is not checked).
 * Mutants:
 *  - POSITIONAL_KEY (`positionalKey`) -> sameRest;
 *  - PUT_SHARED (`putShared`) -> sameRest;
 *  - STRICT_PRIORITY (`strictPriority`) -> boundedWait.
 * Witnesses (checked as not(w), expected VIOLATED): schedulesDiffered (the
 * copies ran the tasks in different orders and still agreed),
 * deficitCarried (a graph kept a deficit across a visit), bothAtRest,
 * waitedToBound (a graph waited exactly BOUND picks: the bound is tight).
 */
module schedule {
  const POSITIONAL_KEY: bool
  const PUT_SHARED: bool
  const STRICT_PRIORITY: bool

  pure val TASKS = Set(1, 2, 3)
  pure def weight(t: int): int = t * 10
  pure val GRAPHS = 3
  pure def COST(g: int): int = if (g == 0) 3 else 1
  pure val QUANTUM = 2
  pure val BOUND = 8

  type Copy = { done: Set[int], order: List[int], shared: int, keys: Set[(int, int)], steps: int }
  pure val fresh: Copy = { done: Set(), order: List(), shared: 0, keys: Set(), steps: 0 }

  type S = {
    c1: Copy, c2: Copy,
    work: List[bool],      // which graphs have work
    deficit: List[int],
    next: int,             // the graph the round robin is on
    granted: bool,         // whether it has had its quantum for this visit
    waited: List[int],     // picks each graph with work has been passed over
    carried: bool,
  }

  var s: S

  action init = s' = {
    c1: fresh, c2: fresh, work: [true, true, false], deficit: [0, 0, 0], next: 0, granted: false, waited: [0, 0, 0], carried: false,
  }

  // ---- schedule independence --------------------------------------------------------------
  pure def runTask(c: Copy, t: int): Copy = {
    ...c,
    done: c.done.union(Set(t)),
    order: c.order.append(t),
    shared: if (PUT_SHARED) weight(t) else c.shared + weight(t),
    keys: c.keys.union(Set((if (POSITIONAL_KEY) c.steps else t, t))),
    steps: c.steps + 1,
  }

  action stepCopy: bool = {
    nondet which = Set(1, 2).oneOf()
    nondet t = TASKS.oneOf()
    val c = if (which == 1) s.c1 else s.c2
    all {
      not(c.done.contains(t)),
      s' = if (which == 1) { ...s, c1: runTask(c, t) } else { ...s, c2: runTask(c, t) },
    }
  }

  // ---- fairness ---------------------------------------------------------------------------
  // which graph runs next, the deficits after, the graph the loop stays on,
  // and whether that graph has had its quantum for this visit. Deficit round
  // robin: the loop stays on a graph while its deficit covers its next step;
  // when it does not, or the graph has no work, the loop moves on, and the
  // next graph it reaches gets a quantum.
  def pickDrr: (int, List[int], int, bool) = {
    0.to(2 * GRAPHS).fold((-1, s.deficit, s.next, s.granted), (acc, _i) =>
      if (acc._1 >= 0) acc
      else {
        val g = acc._3
        if (not(s.work[g])) (-1, acc._2.replaceAt(g, 0), (g + 1) % GRAPHS, false)
        else {
          val d = if (acc._4) acc._2[g] else acc._2[g] + QUANTUM
          if (d >= COST(g)) (g, acc._2.replaceAt(g, d - COST(g)), g, true)
          else (-1, acc._2.replaceAt(g, d), (g + 1) % GRAPHS, false)
        }
      })
  }

  action schedule: bool = {
    val p = if (STRICT_PRIORITY) {
      val g = 0.to(GRAPHS - 1).fold(-1, (b, i) => if (b < 0 and s.work[i]) i else b)
      (g, s.deficit, s.next, s.granted)
    } else pickDrr
    val g = p._1
    nondet more = Set(true, false).oneOf()
    all {
      s.work.foldl(false, (b, w) => b or w),
      s' = { ...s,
        deficit: p._2, next: p._3, granted: p._4,
        waited: 0.to(GRAPHS - 1).fold(s.waited, (w, i) =>
          w.replaceAt(i, if (i == g or not(s.work[i])) 0 else w[i] + 1)),
        carried: s.carried or (g >= 0 and p._2[g] > 0),
        // graph 0 never rests; another graph's step may finish its work
        work: if (g > 0) s.work.replaceAt(g, more) else s.work,
      },
    }
  }

  // work arrives for a resting graph
  action arrive: bool = {
    nondet g = 1.to(GRAPHS - 1).oneOf()
    all { not(s.work[g]), s' = { ...s, work: s.work.replaceAt(g, true) } }
  }

  action step = any { stepCopy, schedule, arrive }

  // ---- properties -------------------------------------------------------------------------
  def atRest(c: Copy): bool = c.done == TASKS
  val sameRest: bool = (atRest(s.c1) and atRest(s.c2)) implies (s.c1.shared == s.c2.shared and s.c1.keys == s.c2.keys)
  val boundedWait: bool = s.waited.foldl(true, (b, w) => b and w <= BOUND)
  val safety: bool = sameRest and boundedWait

  // witnesses
  val schedulesDiffered: bool = atRest(s.c1) and atRest(s.c2) and s.c1.order != s.c2.order
  val deficitCarried: bool = s.carried
  val bothAtRest: bool = atRest(s.c1) and atRest(s.c2)
  val waitedToBound: bool = s.waited.foldl(false, (b, w) => b or w == BOUND)
}

module design {
  import schedule(POSITIONAL_KEY = false, PUT_SHARED = false, STRICT_PRIORITY = false).* from "./schedule"
}
module positionalKey {
  import schedule(POSITIONAL_KEY = true, PUT_SHARED = false, STRICT_PRIORITY = false).* from "./schedule"
}
module putShared {
  import schedule(POSITIONAL_KEY = false, PUT_SHARED = true, STRICT_PRIORITY = false).* from "./schedule"
}
module strictPriority {
  import schedule(POSITIONAL_KEY = false, PUT_SHARED = false, STRICT_PRIORITY = true).* from "./schedule"
}
