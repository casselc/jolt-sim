// -*- mode: Bluespec; -*-
/**
 * caps: capability attenuation with Syndicate-style sturdy refs
 * (docs/cordis/research/PRESERVES.md §6; build order item 3d). A sturdy ref
 * is a macaroon: an object id, a list of caveats, and a signature chained
 * through them, sig = f(...f(f(k, oid), c1)..., cn). Anyone holding a ref
 * can append a caveat and extend the chain; nobody without the root key k
 * can remove one. The gatekeeper resolves a ref to a live capability whose
 * rights are the base rights narrowed by every caveat in turn.
 *
 * Abstraction: a caveat is the set of rights it allows; the chain's MAC is
 * the list of caveats it covers. A holder can derive any extension of what
 * it holds; dropping a caveat leaves a MAC that no longer covers the list.
 *
 * Properties:
 *  - noWidening: every ref the agent holds or can derive that the
 *    gatekeeper accepts resolves to rights within what it was granted;
 *  - attenuates: a derived ref never resolves to more than its parent.
 * Mutants:
 *  - COVERED = false (`uncoveredCaveats`): the MAC covers the object id
 *    alone, so a holder can drop caveats -> noWidening;
 *  - UNION = true (`unionCaveats`): the gatekeeper combines caveats by
 *    union instead of intersection, so appending a caveat widens ->
 *    noWidening.
 * Witnesses (checked as not(w), expected VIOLATED): narrowedTwice,
 * emptyRights.
 */
module caps {
  type Ref = { cav: List[Set[str]], mac: List[Set[str]] }

  const BASE: Set[str]          // the object's full rights
  const GRANT: Set[str]         // the caveat the agent's ref was issued with
  const CAVEATS: Set[Set[str]]  // caveats a holder may append
  const COVERED: bool
  const UNION: bool
  pure val MAX_CAVEATS: int = 3

  var held: Set[Ref]            // every ref the agent holds or has derived

  // The gatekeeper: accept a ref whose MAC covers its caveats, and narrow.
  def accepts(r: Ref): bool = if (COVERED) r.mac == r.cav else true
  def rights(r: Ref): Set[str] =
    if (UNION) r.cav.foldl(Set(), (acc, c) => acc.union(c)).intersect(BASE)
    else r.cav.foldl(BASE, (acc, c) => acc.intersect(c))

  action init = all { held' = Set({ cav: [GRANT], mac: [GRANT] }) }

  // a holder appends a caveat and extends the MAC chain
  action attenuate(r: Ref, c: Set[str]): bool = all {
    held.contains(r), r.cav.length() < MAX_CAVEATS,
    held' = held.union(Set({ cav: r.cav.append(c), mac: r.mac.append(c) })),
  }

  // a holder drops the last caveat; it cannot recompute the MAC
  action strip(r: Ref): bool = all {
    held.contains(r), r.cav.length() > 0,
    held' = held.union(Set({ cav: r.cav.slice(0, r.cav.length() - 1), mac: r.mac })),
  }

  action idle = all { held' = held }

  action step = any {
    nondet r = oneOf(held)
    nondet c = oneOf(CAVEATS)
    any { attenuate(r, c), strip(r) },
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val noWidening: bool = held.forall(r => accepts(r) implies rights(r).subseteq(GRANT))
  val attenuates: bool = held.forall(r =>
    (accepts(r) and r.cav.length() > 1) implies
      rights(r).subseteq(rights({ cav: r.cav.slice(0, r.cav.length() - 1), mac: r.mac.slice(0, r.mac.length() - 1) })))
  val safety: bool = noWidening and attenuates

  // witnesses
  val narrowedTwice: bool = held.exists(r => accepts(r) and r.cav.length() == 3)
  val emptyRights: bool = held.exists(r => accepts(r) and rights(r) == Set())
}

module design {
  import caps(BASE = Set("read", "write", "emit"), GRANT = Set("read", "emit"),
              CAVEATS = Set(Set("read"), Set("emit"), Set("write"), Set("read", "write")),
              COVERED = true, UNION = false).* from "./caps"
}

module uncoveredCaveats {
  import caps(BASE = Set("read", "write", "emit"), GRANT = Set("read", "emit"),
              CAVEATS = Set(Set("read"), Set("emit"), Set("write"), Set("read", "write")),
              COVERED = false, UNION = false).* from "./caps"
}

module unionCaveats {
  import caps(BASE = Set("read", "write", "emit"), GRANT = Set("read", "emit"),
              CAVEATS = Set(Set("read"), Set("emit"), Set("write"), Set("read", "write")),
              COVERED = true, UNION = true).* from "./caps"
}
