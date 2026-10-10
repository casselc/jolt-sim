// -*- mode: Bluespec; -*-
/**
 * revisions: descriptor-native types across graph revisions
 * (docs/cordis/rfc/0004-descriptor-native-types.md, "Revisions").
 *
 * A graph's cells use protobuf message types from descriptor sets the
 * revision attaches by hash. One live revision runs; a candidate revision,
 * attaching a new version of a descriptor set, is proposed, perhaps
 * approved, and admitted by retiring the live fibers and inserting the
 * candidate's. A cell's state is a message, written under some version of
 * its type.
 *
 * Versions: 1 is the live set; a candidate brings 2 (compatible with 1:
 * fields added) or 3 (breaking: a field removed or retyped).
 *
 * What the design does:
 *  - each revision has its own registry: a fiber evaluates with the
 *    registry of the revision it belongs to, never the candidate's
 *    (ownRegistry);
 *  - state crosses a revision only when the new type reads it: carried
 *    across a compatible change, migrated (here: reset to the new type's
 *    default) across a breaking one, so state is never read under a type
 *    that cannot read it (stateReadable);
 *  - a breaking change goes live only approved (breakingApproved);
 *  - cutover retires every old fiber before inserting a new one, so the
 *    state has one writer revision at a time (singleWriter).
 * Mutants:
 *  - GLOBAL_REGISTRY: loading a candidate's sets makes them the registry
 *    every fiber reads -> ownRegistry;
 *  - CARRY_ALWAYS: state is carried across a breaking change unmigrated ->
 *    stateReadable;
 *  - NO_APPROVAL: a breaking candidate is admitted unapproved ->
 *    breakingApproved;
 *  - INSERT_FIRST: the candidate's fibers start before the old ones retire
 *    -> singleWriter.
 * Witnesses (checked as not(w), expected VIOLATED): carriedCompatible,
 * migratedBreaking, evaluatedBesideCandidate.
 */
module revisions {
  const GLOBAL_REGISTRY: bool
  const CARRY_ALWAYS: bool
  const NO_APPROVAL: bool
  const INSERT_FIRST: bool

  pure val NONE = 0
  // what version 'reader' makes of state written under 'writer'
  pure def reads(reader: int, writer: int): bool =
    reader == writer or (reader == 2 and writer == 1)   // 2 adds fields to 1; 3 breaks 1 and 2
  pure def breaking(older: int, newer: int): bool = not(reads(newer, older))

  var liveVersion: int          // the live revision's descriptor version
  var candidate: int            // NONE, or the candidate's version
  var approved: bool
  var oldFibers: bool           // the live revision's fibers are running
  var newFibers: bool           // the candidate's fibers are running (cutover in progress)
  var loaded: int               // the version last loaded anywhere (what a global registry would hold)
  var stateVersion: int         // the version the state was last written under
  var unreadable: bool          // some read used a type that cannot read the state
  var foreignRegistry: bool     // some evaluation used another revision's registry
  var twoWriters: bool          // old and new revisions' fibers ran at once
  var unapprovedBreak: bool
  var carried: bool             // state crossed a compatible change
  var migrated: bool            // state was migrated across a breaking change
  var besideCandidate: bool     // the live revision evaluated while a candidate was pending

  action init = all {
    liveVersion' = 1, candidate' = NONE, approved' = false, oldFibers' = true, newFibers' = false,
    loaded' = 1, stateVersion' = 1, unreadable' = false, foreignRegistry' = false, twoWriters' = false,
    unapprovedBreak' = false, carried' = false, migrated' = false, besideCandidate' = false,
  }

  action unchangedFlags = all {
    unreadable' = unreadable, foreignRegistry' = foreignRegistry, twoWriters' = twoWriters,
    unapprovedBreak' = unapprovedBreak, carried' = carried, migrated' = migrated,
  }

  // a candidate attaching version v is proposed; its sets are loaded
  action propose(v: int): bool = all {
    candidate == NONE, not(newFibers), v != liveVersion,
    candidate' = v, approved' = false, loaded' = v,
    liveVersion' = liveVersion, oldFibers' = oldFibers, newFibers' = newFibers, stateVersion' = stateVersion,
    besideCandidate' = besideCandidate, unchangedFlags,
  }

  action approve: bool = all {
    candidate != NONE, not(approved),
    approved' = true,
    liveVersion' = liveVersion, candidate' = candidate, oldFibers' = oldFibers, newFibers' = newFibers,
    loaded' = loaded, stateVersion' = stateVersion, besideCandidate' = besideCandidate, unchangedFlags,
  }

  // a live fiber evaluates: reads the state, writes it back
  action evaluateLive: bool = {
    val registry = if (GLOBAL_REGISTRY) loaded else liveVersion
    all {
      oldFibers,
      unreadable' = (unreadable or not(reads(registry, stateVersion))),
      foreignRegistry' = (foreignRegistry or registry != liveVersion),
      twoWriters' = (twoWriters or newFibers),
      stateVersion' = if (reads(registry, stateVersion)) registry else stateVersion,
      besideCandidate' = (besideCandidate or candidate != NONE),
      liveVersion' = liveVersion, candidate' = candidate, approved' = approved, oldFibers' = oldFibers,
      newFibers' = newFibers, loaded' = loaded,
      unapprovedBreak' = unapprovedBreak, carried' = carried, migrated' = migrated,
    }
  }

  // admission: cutover begins. The state crosses the change here.
  action admit: bool = {
    val isBreak = breaking(liveVersion, candidate)
    val carry = reads(candidate, stateVersion) or CARRY_ALWAYS
    all {
      candidate != NONE, not(newFibers),
      isBreak implies (approved or NO_APPROVAL),
      unapprovedBreak' = (unapprovedBreak or (isBreak and not(approved))),
      // retire first (design), or start the new fibers beside the old
      oldFibers' = INSERT_FIRST,
      newFibers' = true,
      stateVersion' = if (carry) stateVersion else candidate,   // not carried: migrated to the new type
      carried' = (carried or (reads(candidate, stateVersion) and not(isBreak) and stateVersion != candidate)),
      migrated' = (migrated or not(carry)),
      liveVersion' = liveVersion, candidate' = candidate, approved' = approved, loaded' = loaded,
      unreadable' = unreadable, foreignRegistry' = foreignRegistry, twoWriters' = twoWriters,
      besideCandidate' = besideCandidate,
    }
  }

  // a new fiber evaluates under the candidate's registry
  action evaluateNew: bool = all {
    newFibers,
    unreadable' = (unreadable or not(reads(candidate, stateVersion))),
    twoWriters' = (twoWriters or oldFibers),
    stateVersion' = if (reads(candidate, stateVersion)) candidate else stateVersion,
    liveVersion' = liveVersion, candidate' = candidate, approved' = approved, oldFibers' = oldFibers,
    newFibers' = newFibers, loaded' = loaded, foreignRegistry' = foreignRegistry,
    unapprovedBreak' = unapprovedBreak, carried' = carried, migrated' = migrated, besideCandidate' = besideCandidate,
  }

  // the old fibers finish retiring (INSERT_FIRST only), then cutover completes
  action retireOld: bool = all {
    oldFibers, newFibers,
    oldFibers' = false,
    liveVersion' = liveVersion, candidate' = candidate, approved' = approved, newFibers' = newFibers,
    loaded' = loaded, stateVersion' = stateVersion, besideCandidate' = besideCandidate, unchangedFlags,
  }

  action complete: bool = all {
    newFibers, not(oldFibers),
    liveVersion' = candidate, candidate' = NONE, approved' = false, oldFibers' = true, newFibers' = false,
    loaded' = loaded, stateVersion' = stateVersion, besideCandidate' = besideCandidate, unchangedFlags,
  }

  // a pending candidate may be withdrawn
  action withdraw: bool = all {
    candidate != NONE, not(newFibers),
    candidate' = NONE, approved' = false,
    // a global registry keeps what was loaded last
    loaded' = if (GLOBAL_REGISTRY) loaded else liveVersion,
    liveVersion' = liveVersion, oldFibers' = oldFibers, newFibers' = newFibers, stateVersion' = stateVersion,
    besideCandidate' = besideCandidate, unchangedFlags,
  }

  action idle = all {
    liveVersion' = liveVersion, candidate' = candidate, approved' = approved, oldFibers' = oldFibers,
    newFibers' = newFibers, loaded' = loaded, stateVersion' = stateVersion, besideCandidate' = besideCandidate,
    unchangedFlags,
  }

  action step = any {
    nondet v = oneOf(Set(1, 2, 3))
    propose(v),
    approve,
    evaluateLive,
    admit,
    evaluateNew,
    retireOld,
    complete,
    withdraw,
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val ownRegistry: bool = not(foreignRegistry)
  val stateReadable: bool = not(unreadable)
  val breakingApproved: bool = not(unapprovedBreak)
  val singleWriter: bool = not(twoWriters)
  val safety: bool = ownRegistry and stateReadable and breakingApproved and singleWriter

  // witnesses
  val carriedCompatible: bool = carried
  val migratedBreaking: bool = migrated
  val evaluatedBesideCandidate: bool = besideCandidate
}

module design {
  import revisions(GLOBAL_REGISTRY = false, CARRY_ALWAYS = false, NO_APPROVAL = false, INSERT_FIRST = false).* from "./revisions"
}

module globalRegistry {
  import revisions(GLOBAL_REGISTRY = true, CARRY_ALWAYS = false, NO_APPROVAL = false, INSERT_FIRST = false).* from "./revisions"
}

module carryAlways {
  import revisions(GLOBAL_REGISTRY = false, CARRY_ALWAYS = true, NO_APPROVAL = false, INSERT_FIRST = false).* from "./revisions"
}

module noApproval {
  import revisions(GLOBAL_REGISTRY = false, CARRY_ALWAYS = false, NO_APPROVAL = true, INSERT_FIRST = false).* from "./revisions"
}

module insertFirst {
  import revisions(GLOBAL_REGISTRY = false, CARRY_ALWAYS = false, NO_APPROVAL = false, INSERT_FIRST = true).* from "./revisions"
}
