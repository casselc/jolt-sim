// -*- mode: Bluespec; -*-
/**
 * recovery: durable versus process-scoped acquisitions, and the recovery
 * sweep (docs/cordis/rfc/0006-turn-runtime.md, "Extensions to existing
 * models"; kept apart from lifecycle.qnt, whose variables are the
 * simulator's conformance target).
 *
 * Fibers acquire two kinds of thing. A process-scoped acquisition (a file
 * handle, a socket) dies with the process. A durable one (a lease, a lock,
 * a checked-out worktree) outlives it: the world holds it until it is
 * released, so the run store records it, with a generation, in the same
 * record as the acquisition, and records its release the same way. A fiber
 * releases its durable acquisitions before it unloads.
 *
 * A crash kills the process: its process-scoped acquisitions go, and
 * recovery reinstalls some of the fibers the records show (one may have
 * been retired, or belong to a revision no longer live). Before anything
 * runs, the sweep releases every recorded durable acquisition whose owner
 * was not reinstalled, by its generation (a fenced release: it frees the
 * thing only if that generation still holds it), and records the release.
 *
 * Properties:
 *  - noLeakedDurable: outside recovery, everything the world holds is held
 *    by an installed fiber;
 *  - releasedOnce: no acquisition (generation) is released twice: a stale
 *    release could free what another fiber has since acquired;
 *  - procOnlyWhenRun: process-scoped acquisitions belong to installed
 *    fibers.
 * Mutants:
 *  - NO_SWEEP (`noSweep`): recovery reinstalls and runs with no sweep ->
 *    noLeakedDurable;
 *  - SWEEP_TWICE (`sweepTwice`): the sweep releases without recording the
 *    release, so the next recovery (or the owner, reinstalled later)
 *    releases it again -> releasedOnce.
 * Witnesses (checked as not(w), expected VIOLATED): sweptOrphan (a sweep
 * released an acquisition), reacquiredAfterSweep (another fiber acquired
 * what a sweep released), crashedHolding (a crash struck with a durable
 * acquisition held), recoveredOwner (a fiber reinstalled by recovery kept
 * its durable acquisition).
 */
module recovery {
  const NO_SWEEP: bool
  const SWEEP_TWICE: bool

  pure val FIBERS = Set(1, 2)
  pure val RES = Set("a", "b")
  pure val FREE = (0, 0)
  pure val MAX_GEN = 6
  pure val MAX_CRASHES = 2

  var installed: int -> bool
  var records: Set[(str, int, int)]   // durable acquisitions recorded and not released: (thing, generation, owner)
  var holder: str -> (int, int)       // the world: who holds each thing, and under which generation
  var handles: int -> Set[str]        // process-scoped acquisitions
  var nextGen: int
  var crashes: int
  var recovering: bool
  var sweptNow: Set[int]              // generations released by this recovery's sweep
  // ghosts
  var released: Set[int]
  var doubleRelease: bool
  var sweptGens: Set[(str, int)]     // (thing, generation) released by a sweep
  var reacquired: bool
  var crashedHeld: bool
  var keptOwner: bool

  action init = all {
    installed' = FIBERS.mapBy(_ => false), records' = Set(), holder' = RES.mapBy(_ => FREE),
    handles' = FIBERS.mapBy(_ => Set()), nextGen' = 1, crashes' = 0, recovering' = false, sweptNow' = Set(),
    released' = Set(), doubleRelease' = false, sweptGens' = Set(), reacquired' = false, crashedHeld' = false,
    keptOwner' = false,
  }

  def owns(n: int): Set[(str, int, int)] = records.filter(x => x._3 == n)
  def orphans: Set[(str, int, int)] = records.filter(x => not(installed.get(x._3)))

  // release generation g of thing r: the world frees r only if g still holds it
  def releaseGen(r: str, g: int): str -> (int, int) = if (holder.get(r)._2 == g) holder.set(r, FREE) else holder

  action unchangedGhosts = all {
    released' = released, doubleRelease' = doubleRelease, sweptGens' = sweptGens, reacquired' = reacquired,
    crashedHeld' = crashedHeld, keptOwner' = keptOwner,
  }

  action install(n: int): bool = all {
    not(recovering), not(installed.get(n)),
    installed' = installed.set(n, true),
    records' = records, holder' = holder, handles' = handles, nextGen' = nextGen, crashes' = crashes,
    recovering' = recovering, sweptNow' = sweptNow, unchangedGhosts,
  }

  // the acquisition and its record are one step (the outbox's journal covers the gap, as for any effect)
  action acquireDurable(n: int, r: str): bool = all {
    not(recovering), installed.get(n), holder.get(r) == FREE, nextGen <= MAX_GEN,
    records' = records.union(Set((r, nextGen, n))),
    holder' = holder.set(r, (n, nextGen)),
    nextGen' = nextGen + 1,
    reacquired' = (reacquired or sweptGens.exists(x => x._1 == r)),
    installed' = installed, handles' = handles, crashes' = crashes, recovering' = recovering, sweptNow' = sweptNow,
    released' = released, doubleRelease' = doubleRelease, sweptGens' = sweptGens, crashedHeld' = crashedHeld,
    keptOwner' = keptOwner,
  }

  action acquireProc(n: int, r: str): bool = all {
    not(recovering), installed.get(n),
    handles' = handles.setBy(n, h => h.union(Set(r))),
    installed' = installed, records' = records, holder' = holder, nextGen' = nextGen, crashes' = crashes,
    recovering' = recovering, sweptNow' = sweptNow, unchangedGhosts,
  }

  action releaseDurable(n: int, r: str, g: int): bool = all {
    not(recovering), installed.get(n), records.contains((r, g, n)),
    holder' = releaseGen(r, g),
    records' = records.exclude(Set((r, g, n))),
    released' = released.union(Set(g)), doubleRelease' = (doubleRelease or released.contains(g)),
    installed' = installed, handles' = handles, nextGen' = nextGen, crashes' = crashes,
    recovering' = recovering, sweptNow' = sweptNow,
    sweptGens' = sweptGens, reacquired' = reacquired, crashedHeld' = crashedHeld, keptOwner' = keptOwner,
  }

  // a fiber unloads once its durable acquisitions are released; its handles close with it
  action unload(n: int): bool = all {
    not(recovering), installed.get(n), owns(n) == Set(),
    installed' = installed.set(n, false), handles' = handles.set(n, Set()),
    records' = records, holder' = holder, nextGen' = nextGen, crashes' = crashes,
    recovering' = recovering, sweptNow' = sweptNow, unchangedGhosts,
  }

  // the process dies: handles go; recovery reinstalls some of the installed fibers
  action crash: bool = {
    nondet keep = FIBERS.filter(n => installed.get(n)).powerset().oneOf()
    all {
      crashes < MAX_CRASHES,
      crashes' = crashes + 1,
      installed' = FIBERS.mapBy(n => keep.contains(n)),
      handles' = FIBERS.mapBy(_ => Set()),
      recovering' = not(NO_SWEEP),
      sweptNow' = Set(),
      crashedHeld' = (crashedHeld or records != Set()),
      keptOwner' = (keptOwner or records.exists(x => keep.contains(x._3))),
      records' = records, holder' = holder, nextGen' = nextGen,
      released' = released, doubleRelease' = doubleRelease, sweptGens' = sweptGens, reacquired' = reacquired,
    }
  }

  // the sweep: release an orphaned durable acquisition, by its generation, and record the release
  action sweep(r: str, g: int, n: int): bool = all {
    recovering, orphans.contains((r, g, n)), not(sweptNow.contains(g)),
    holder' = releaseGen(r, g),
    records' = if (SWEEP_TWICE) records else records.exclude(Set((r, g, n))),
    sweptNow' = sweptNow.union(Set(g)),
    released' = released.union(Set(g)), doubleRelease' = (doubleRelease or released.contains(g)),
    sweptGens' = sweptGens.union(Set((r, g))),
    installed' = installed, handles' = handles, nextGen' = nextGen, crashes' = crashes, recovering' = recovering,
    reacquired' = reacquired, crashedHeld' = crashedHeld, keptOwner' = keptOwner,
  }

  // recovery ends when every orphan has been swept
  action recovered: bool = all {
    recovering, orphans.forall(x => sweptNow.contains(x._2)),
    recovering' = false,
    installed' = installed, records' = records, holder' = holder, handles' = handles, nextGen' = nextGen,
    crashes' = crashes, sweptNow' = sweptNow, unchangedGhosts,
  }

  action idle = all {
    installed' = installed, records' = records, holder' = holder, handles' = handles, nextGen' = nextGen,
    crashes' = crashes, recovering' = recovering, sweptNow' = sweptNow, unchangedGhosts,
  }

  action step = {
    nondet n = FIBERS.oneOf()
    nondet r = RES.oneOf()
    nondet g = 1.to(MAX_GEN).oneOf()
    any {
      install(n), acquireDurable(n, r), acquireProc(n, r), releaseDurable(n, r, g), unload(n),
      crash, sweep(r, g, n), recovered, idle,
    }
  }

  // ---- properties -------------------------------------------------------------------------
  val noLeakedDurable: bool = not(recovering) implies RES.forall(r => holder.get(r) == FREE or installed.get(holder.get(r)._1))
  val releasedOnce: bool = not(doubleRelease)
  val procOnlyWhenRun: bool = FIBERS.forall(n => not(installed.get(n)) implies handles.get(n) == Set())
  val safety: bool = noLeakedDurable and releasedOnce and procOnlyWhenRun

  // witnesses
  val sweptOrphan: bool = sweptGens != Set()
  val reacquiredAfterSweep: bool = reacquired
  val crashedHolding: bool = crashedHeld
  val recoveredOwner: bool = keptOwner
}

module design {
  import recovery(NO_SWEEP = false, SWEEP_TWICE = false).* from "./recovery"
}
module noSweep {
  import recovery(NO_SWEEP = true, SWEEP_TWICE = false).* from "./recovery"
}
module sweepTwice {
  import recovery(NO_SWEEP = false, SWEEP_TWICE = true).* from "./recovery"
}
