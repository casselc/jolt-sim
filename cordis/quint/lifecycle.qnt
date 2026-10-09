// -*- mode: Bluespec; -*-
/**
 * lifecycle: the Cordis lifecycle calculus (Cordis §4.2) with the outbox
 * additions of docs/cordis/DESIGN.md "The model": an iteration may end in
 * one emission, released after its turn commits and answered by a receipt;
 * a lost receipt puts the fiber in Uncertain, which accepts only reconcile
 * and blocks unloading; a failed receipt unwinds the fiber to Failed, for a
 * supervisor to restart.
 *
 * This is the hand-written model of build-order item 2. It abstracts the
 * kernel (cordis/kernel/kernel.ml) to what the theorems need: which keys
 * each fiber's table binds, its lifecycle state, its committed view, and
 * how many times each emission took effect in the world. Values in tables
 * are left out. The simulator writes its runs as ITF traces over exactly
 * these variables, and quintgo replays them here (conformance).
 *
 * Shape of a component: requires REQ(n), provides PROV(n), runs ITERS(n)
 * iterations; all of PROV(n) is bound by iteration PROV_AT(n); iteration i
 * ends in an emission when i is in EMIT_AT(n).
 *
 * Properties:
 *  - guardHolds (Cordis Theorem 70, ordering): an installed fiber's
 *    committed view names only installed providers, so a provider outlives
 *    every consumer that resolved a key to it;
 *  - atMostOnce: no emission takes effect twice. An emission is one
 *    iteration's intent in one episode: a fiber that restarts and runs the
 *    iteration again makes a new emission (its idempotency key carries the
 *    episode), and the simulator found that without the episode a restart
 *    reads as a duplicate;
 *  - boundOnlyWhenRun: a table binds keys only while its fiber is installed
 *    (terminal recovery, Cordis Theorem 68, at the abstraction).
 * Mutants:
 *  - GUARD = false (`noGuard`): unload ignores relied;
 *  - RECONCILE = false (`blindRetry`): an Uncertain intent is sent again
 *    instead of reconciled against the world.
 * Witnesses (checked as not(w), expected VIOLATED in simulation):
 * someUncertain, guardWaiting, someFailed, failingUnload, restartedEmits.
 */
module lifecycle {
  type Life =
    | Inactive
    | Reloading(int)   // iterations done, none in flight
    | Awaiting(int)    // iterations done; the last released an intent with no receipt yet
    | Active
    | Uncertain(int)   // the last intent's receipt was lost
    | Unloading(bool)  // failing: true when a receipt or reconcile failed
    | Failed

  const FIBERS: Set[int]
  const REQ: int -> Set[str]
  const PROV: int -> Set[str]
  const ITERS: int -> int
  const PROV_AT: int -> int
  const EMIT_AT: int -> Set[int]
  const RETIRABLE: Set[int]
  const GUARD: bool
  const RECONCILE: bool

  var life: int -> Life
  var view: int -> (str -> int)
  var retired: int -> bool
  var bound: int -> Set[str]
  var episode: int -> int   // fiber -> how many times it has begun
  var applied: int -> int   // emission (iid * 1000 + episode) -> times it took effect
  var hidden: int -> bool   // fiber -> did its last lost intent take effect (the world's side)

  pure def iid(n: int, i: int): int = n * 100 + i
  pure def eid(i: int, e: int): int = i * 1000 + e
  def bump(k: int): int -> int = applied.put(k, if (applied.keys().contains(k)) applied.get(k) + 1 else 1)

  pure def pcOf(l: Life): int = match l {
    | Reloading(p) => p
    | Awaiting(p) => p
    | Uncertain(p) => p
    | _ => -1
  }
  pure def isReloading(l: Life): bool = match l { | Reloading(_) => true | _ => false }
  pure def isAwaiting(l: Life): bool = match l { | Awaiting(_) => true | _ => false }
  pure def isUncertain(l: Life): bool = match l { | Uncertain(_) => true | _ => false }
  pure def isUnloading(l: Life): bool = match l { | Unloading(_) => true | _ => false }
  pure def failing(l: Life): bool = match l { | Unloading(f) => f | _ => false }

  def installedF(n: int): bool = life.get(n) != Inactive and life.get(n) != Failed
  def providers(k: str): Set[int] = FIBERS.filter(m => life.get(m) == Active and bound.get(m).contains(k))
  def hasTarget(n: int): bool = not(retired.get(n)) and REQ.get(n).forall(k => providers(k).size() > 0)
  def targetView(n: int): str -> int = REQ.get(n).mapBy(k => providers(k).fold(-1, (a, m) => m))
  def onTarget(n: int): bool = hasTarget(n) and targetView(n) == view.get(n)
  def names(m: int, n: int): bool = view.get(m).keys().exists(k => view.get(m).get(k) == n)
  def reliedOn(n: int): bool = FIBERS.exists(m => m != n and installedF(m) and names(m, n))

  action keepWorld = all { applied' = applied, hidden' = hidden, episode' = episode }

  action init = all {
    life' = FIBERS.mapBy(_ => Inactive),
    view' = FIBERS.mapBy(_ => Map()),
    retired' = FIBERS.mapBy(_ => false),
    bound' = FIBERS.mapBy(_ => Set()),
    applied' = Map(),
    episode' = FIBERS.mapBy(_ => 0),
    hidden' = FIBERS.mapBy(_ => false),
  }

  // ---- lifecycle rules (L-) -------------------------------------------------------------
  action begin(n: int): bool = all {
    life.get(n) == Inactive, hasTarget(n),
    life' = life.set(n, Reloading(0)), view' = view.set(n, targetView(n)),
    episode' = episode.setBy(n, e => e + 1),
    retired' = retired, bound' = bound, applied' = applied, hidden' = hidden,
  }

  action iter(n: int): bool = {
    val pc = pcOf(life.get(n))
    all {
      isReloading(life.get(n)), pc < ITERS.get(n), onTarget(n),
      life' = life.set(n, if (EMIT_AT.get(n).contains(pc)) Awaiting(pc + 1) else Reloading(pc + 1)),
      bound' = if (pc == PROV_AT.get(n)) bound.set(n, PROV.get(n)) else bound,
      view' = view, retired' = retired, keepWorld,
    }
  }

  action finish(n: int): bool = all {
    isReloading(life.get(n)), pcOf(life.get(n)) == ITERS.get(n), onTarget(n),
    life' = life.set(n, Active),
    view' = view, retired' = retired, bound' = bound, keepWorld,
  }

  action divert(n: int): bool = all {
    isReloading(life.get(n)), not(onTarget(n)),
    life' = life.set(n, Unloading(false)),
    view' = view, retired' = retired, bound' = bound, keepWorld,
  }

  action leave(n: int): bool = all {
    life.get(n) == Active, not(onTarget(n)),
    life' = life.set(n, Unloading(false)),
    view' = view, retired' = retired, bound' = bound, keepWorld,
  }

  action unload(n: int): bool = all {
    isUnloading(life.get(n)), not(GUARD) or not(reliedOn(n)),
    life' = life.set(n, if (failing(life.get(n))) Failed else Inactive),
    view' = view.set(n, Map()), bound' = bound.set(n, Set()),
    retired' = retired, keepWorld,
  }

  // ---- the outbox's answers ---------------------------------------------------------------
  // r is "definite", "failed" or "lost"; took is the world's side of a lost receipt.
  action deliver(n: int, r: str, took: bool): bool = {
    val pc = pcOf(life.get(n))
    val i = eid(iid(n, pc - 1), episode.get(n))
    all {
      isAwaiting(life.get(n)),
      Set("definite", "failed", "lost").contains(r),
      r == "lost" or not(took),
      life' = life.set(n, if (r == "definite") Reloading(pc)
                          else if (r == "failed") Unloading(true)
                          else Uncertain(pc)),
      applied' = if (r == "definite" or took) bump(i) else applied,
      hidden' = if (r == "lost") hidden.set(n, took) else hidden,
      view' = view, retired' = retired, bound' = bound, episode' = episode,
    }
  }

  // Design: read the world (took must be what happened). Mutant: send again,
  // and took is whether the second copy lands.
  action reconcile(n: int, took: bool): bool = {
    val pc = pcOf(life.get(n))
    val i = eid(iid(n, pc - 1), episode.get(n))
    all {
      isUncertain(life.get(n)),
      if (RECONCILE) all {
        took == hidden.get(n),
        life' = life.set(n, if (took) Reloading(pc) else Unloading(true)),
        applied' = applied,
      } else all {
        life' = life.set(n, Reloading(pc)),
        applied' = if (took) bump(i) else applied,
      },
      hidden' = hidden, view' = view, retired' = retired, bound' = bound, episode' = episode,
    }
  }

  // ---- orchestration (O-) -----------------------------------------------------------------
  action restart(n: int): bool = all {
    life.get(n) == Failed, not(retired.get(n)),
    life' = life.set(n, Inactive),
    view' = view, retired' = retired, bound' = bound, keepWorld,
  }

  action retire(n: int): bool = all {
    RETIRABLE.contains(n), not(retired.get(n)),
    retired' = retired.set(n, true),
    life' = life, view' = view, bound' = bound, keepWorld,
  }

  action idle = all {
    life' = life, view' = view, retired' = retired, bound' = bound, keepWorld,
  }

  action step = any {
    nondet n = oneOf(FIBERS)
    nondet r = oneOf(Set("definite", "failed", "lost"))
    nondet took = oneOf(Set(true, false))
    any {
      begin(n), iter(n), finish(n), divert(n), leave(n), unload(n),
      deliver(n, r, took), reconcile(n, took), restart(n), retire(n),
    },
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val guardHolds: bool = FIBERS.forall(m =>
    installedF(m) implies view.get(m).keys().forall(k => installedF(view.get(m).get(k))))
  val atMostOnce: bool = applied.keys().forall(i => applied.get(i) <= 1)
  val boundOnlyWhenRun: bool = FIBERS.forall(n => not(installedF(n)) implies bound.get(n) == Set())
  val safety: bool = guardHolds and atMostOnce and boundOnlyWhenRun

  // Witnesses: states that must be reachable. Checked as `not(w)`, which
  // simulation is expected to violate; an unviolated `not(w)` is a coverage
  // gap, never a pass.
  val someUncertain: bool = FIBERS.exists(n => isUncertain(life.get(n)))
  val guardWaiting: bool = FIBERS.exists(n => isUnloading(life.get(n)) and reliedOn(n))
  val someFailed: bool = FIBERS.exists(n => life.get(n) == Failed)
  val failingUnload: bool = FIBERS.exists(n => failing(life.get(n)))
  val restartedEmits: bool = FIBERS.exists(n => episode.get(n) > 1 and isAwaiting(life.get(n)))
}

// A chain: 1 provides db; 2 reads db, provides api and emits; 3 reads api.
module design {
  import lifecycle(
    FIBERS = Set(1, 2, 3),
    REQ = Map(1 -> Set(), 2 -> Set("db"), 3 -> Set("api")),
    PROV = Map(1 -> Set("db"), 2 -> Set("api"), 3 -> Set()),
    ITERS = Map(1 -> 1, 2 -> 2, 3 -> 1),
    PROV_AT = Map(1 -> 0, 2 -> 1, 3 -> -1),
    EMIT_AT = Map(1 -> Set(), 2 -> Set(0), 3 -> Set(0)),
    RETIRABLE = Set(1, 3),
    GUARD = true,
    RECONCILE = true
  ).* from "./lifecycle"
}

module noGuard {
  import lifecycle(
    FIBERS = Set(1, 2, 3),
    REQ = Map(1 -> Set(), 2 -> Set("db"), 3 -> Set("api")),
    PROV = Map(1 -> Set("db"), 2 -> Set("api"), 3 -> Set()),
    ITERS = Map(1 -> 1, 2 -> 2, 3 -> 1),
    PROV_AT = Map(1 -> 0, 2 -> 1, 3 -> -1),
    EMIT_AT = Map(1 -> Set(), 2 -> Set(0), 3 -> Set(0)),
    RETIRABLE = Set(1, 3),
    GUARD = false,
    RECONCILE = true
  ).* from "./lifecycle"
}

module blindRetry {
  import lifecycle(
    FIBERS = Set(1, 2, 3),
    REQ = Map(1 -> Set(), 2 -> Set("db"), 3 -> Set("api")),
    PROV = Map(1 -> Set("db"), 2 -> Set("api"), 3 -> Set()),
    ITERS = Map(1 -> 1, 2 -> 2, 3 -> 1),
    PROV_AT = Map(1 -> 0, 2 -> 1, 3 -> -1),
    EMIT_AT = Map(1 -> Set(), 2 -> Set(0), 3 -> Set(0)),
    RETIRABLE = Set(1, 3),
    GUARD = true,
    RECONCILE = false
  ).* from "./lifecycle"
}
