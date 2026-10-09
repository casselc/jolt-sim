// -*- mode: Bluespec; -*-
/**
 * branches: one step snapshot restored into several VMs at once, for soak
 * in branched futures (docs/cordis/DESIGN.md, "Where the outbox runs",
 * Branches; build order item 3b).
 *
 * Each restored VM is a branch with its own fork ID. Every branch's guest
 * issues emissions under idempotency keys drawn from the entropy oracle, so
 * branches that do the same work draw the same keys and branches that
 * diverge draw different ones (KEYS(b)). The agent's one outbox records
 * every branch's intents under (fork ID, key) but releases nothing until a
 * branch is chosen to continue. Then it releases the chosen branch's
 * intents, deduplicated by key, and answers every other branch's intents
 * with simulated receipts from the replay cassette.
 *
 * Properties:
 *  - onlyChosen: an emission takes effect only if the chosen branch issued it;
 *  - atMostOnce: no key takes effect twice;
 *  - realOnlyToChosen: only the chosen branch receives real receipts;
 *  - answered: once a branch is chosen, every recorded intent has a receipt.
 * Mutants:
 *  - RELEASE_EARLY (`releaseEarly`): the outbox releases intents as they
 *    are recorded -> onlyChosen. With DEDUP on, atMostOnce still holds: the
 *    oracle-drawn keys catch identical branches' duplicates even when the
 *    hold-back fails (checked as a design property of this instance);
 *  - RELEASE_EARLY and DEDUP = false (`releaseEarlyNoDedup`) -> atMostOnce;
 *  - FORK_IDS = false (`noForkIds`): the outbox records and answers by key
 *    alone, so a receipt reaches every branch that issued the key ->
 *    realOnlyToChosen.
 * Witnesses (checked as not(w), expected VIOLATED): sharedKeyHeldBack,
 * divergentSimulated, chosenGotReal.
 */
module branches {
  const BRANCHES: Set[int]
  const KEYS: int -> Set[int]
  const RELEASE_EARLY: bool
  const DEDUP: bool
  const FORK_IDS: bool

  pure val NONE: int = 0
  val ALL_KEYS: Set[int] = BRANCHES.fold(Set(), (s, b) => s.union(KEYS.get(b)))

  var chosen: int                       // NONE until a branch is chosen
  var issued: Set[(int, int)]           // (fork ID, key) recorded by the outbox
  var released: Set[int]                // keys sent to the world
  var applied: int -> int               // key -> times it took effect
  var receipts: Set[(int, int, str)]    // (fork ID, key, "real" | "simulated")

  action init = all {
    chosen' = NONE, issued' = Set(), released' = Set(),
    applied' = ALL_KEYS.mapBy(_ => 0), receipts' = Set(),
  }

  // Send key k to the world: once per key with DEDUP, every time without.
  def sendOnce(ks: Set[int]): int -> int =
    ALL_KEYS.mapBy(k =>
      if (ks.contains(k) and (not(DEDUP) or not(released.contains(k)))) applied.get(k) + 1
      else applied.get(k))

  // The receipts a real answer for (b, k) produces: to b alone, or, without
  // fork IDs, to every branch that recorded k.
  def realTo(b: int, k: int, rec: Set[(int, int)]): Set[(int, int, str)] =
    if (FORK_IDS) Set((b, k, "real"))
    else rec.filter(x => x._2 == k).map(x => (x._1, k, "real"))

  // A branch's guest asks for an emission.
  action issue(b: int, k: int): bool = {
    val rec = issued.union(Set((b, k)))
    val release = RELEASE_EARLY or chosen == b
    all {
      KEYS.get(b).contains(k), not(issued.contains((b, k))),
      issued' = rec,
      chosen' = chosen,
      if (release) all {
        applied' = sendOnce(Set(k)),
        released' = released.union(Set(k)),
        receipts' = receipts.union(realTo(b, k, rec)),
      } else if (chosen != NONE) all {
        // a branch that was not chosen: answered from the cassette
        applied' = applied, released' = released,
        receipts' = receipts.union(Set((b, k, "simulated"))),
      } else all {
        // no choice yet: held
        applied' = applied, released' = released, receipts' = receipts,
      },
    }
  }

  // The soak picks the branch that continues.
  action choose(b: int): bool = {
    val mine = issued.filter(x => x._1 == b).map(x => x._2)
    val others = issued.filter(x => x._1 != b)
    all {
      chosen == NONE, BRANCHES.contains(b),
      chosen' = b,
      issued' = issued,
      applied' = sendOnce(mine),
      released' = released.union(mine),
      receipts' = receipts
        .union(mine.fold(Set(), (s, k) => s.union(realTo(b, k, issued))))
        .union(others.filter(x => not(receipts.exists(r => r._1 == x._1 and r._2 == x._2)))
                     .map(x => (x._1, x._2, "simulated"))),
    }
  }

  // nothing left to do (every branch has issued, a branch is chosen)
  action idle = all {
    chosen' = chosen, issued' = issued, released' = released, applied' = applied, receipts' = receipts,
  }

  action step = any {
    nondet b = oneOf(BRANCHES)
    nondet k = oneOf(ALL_KEYS)
    any { issue(b, k), choose(b) },
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val onlyChosen: bool = ALL_KEYS.forall(k =>
    applied.get(k) > 0 implies (chosen != NONE and issued.contains((chosen, k))))
  val atMostOnce: bool = ALL_KEYS.forall(k => applied.get(k) <= 1)
  val realOnlyToChosen: bool = receipts.forall(r => r._3 == "real" implies r._1 == chosen)
  val answered: bool = chosen != NONE implies
    issued.forall(x => receipts.exists(r => r._1 == x._1 and r._2 == x._2))
  val safety: bool = onlyChosen and atMostOnce and realOnlyToChosen and answered

  // witnesses
  val sharedKeyHeldBack: bool = chosen != NONE and
    issued.exists(x => x._1 != chosen and issued.contains((chosen, x._2)))
  val divergentSimulated: bool = receipts.exists(r =>
    r._3 == "simulated" and chosen != NONE and not(KEYS.get(chosen).contains(r._2)))
  val chosenGotReal: bool = chosen != NONE and receipts.exists(r => r._1 == chosen and r._3 == "real")
}

// Branches 1 and 2 do the same work (the same keys); branch 3 diverges.
module design {
  import branches(BRANCHES = Set(1, 2, 3), KEYS = Map(1 -> Set(1, 2), 2 -> Set(1, 2), 3 -> Set(1, 3)),
                  RELEASE_EARLY = false, DEDUP = true, FORK_IDS = true).* from "./branches"
}

module releaseEarly {
  import branches(BRANCHES = Set(1, 2, 3), KEYS = Map(1 -> Set(1, 2), 2 -> Set(1, 2), 3 -> Set(1, 3)),
                  RELEASE_EARLY = true, DEDUP = true, FORK_IDS = true).* from "./branches"
}

module releaseEarlyNoDedup {
  import branches(BRANCHES = Set(1, 2, 3), KEYS = Map(1 -> Set(1, 2), 2 -> Set(1, 2), 3 -> Set(1, 3)),
                  RELEASE_EARLY = true, DEDUP = false, FORK_IDS = true).* from "./branches"
}

module noForkIds {
  import branches(BRANCHES = Set(1, 2, 3), KEYS = Map(1 -> Set(1, 2), 2 -> Set(1, 2), 3 -> Set(1, 3)),
                  RELEASE_EARLY = false, DEDUP = true, FORK_IDS = false).* from "./branches"
}
