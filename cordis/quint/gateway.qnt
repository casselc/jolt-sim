// -*- mode: Bluespec; -*-
/**
 * gateway: tool calls through the platform's MCP/A2A gateway, and whether a
 * call is internal (replayed freely, rolled back with the step) or an
 * emission (journaled in the outbox, released at most once)
 * (docs/cordis/DESIGN.md, build order item 3c).
 *
 * A tool is either remote (its traffic crosses `pf`, so only an outbox jail
 * may send it) or hosted on the platform (its MCP server runs in a jail of
 * its own, inside `pf`). A hosted server may itself reach the outside world
 * while serving a call (EGRESS); its MCP `openWorldHint` annotation (ANNOT:
 * "open", "closed" or "none") is supposed to say so, and can be wrong.
 *
 * The gateway classes a call as an emission when `pf` says it crosses, or
 * when its annotation is "open" or missing; as internal only when the tool
 * is hosted and annotated "closed". An internal call is executed again when
 * the agent's step is rolled back; an emission is answered from the journal.
 *
 * What the model shows: classification is a hint and can be wrong, so it
 * cannot be what keeps effects single. The design also routes a hosted
 * server's own egress through an outbox keyed by the calling emission's
 * idempotency key (GATED), which the gateway passes in the MCP request's
 * metadata. Then a lying "closed" annotation costs a replay, not a
 * duplicate effect.
 *
 * Properties:
 *  - atMostOnce: no call has an outside effect twice;
 *  - remoteViaOutbox: every outside effect of a remote tool left through
 *    the agent's outbox.
 * Mutants:
 *  - GATED = false (`ungated`): hosted servers reach out directly; a server
 *    annotated "closed" that does reach out has its call replayed on
 *    rollback -> atMostOnce;
 *  - DEFAULT_INTERNAL (`missingIsInternal`, with GATED = false): a missing
 *    annotation is read as "closed" -> atMostOnce.
 * Witnesses (checked as not(w), expected VIOLATED): misannotatedReplayed
 * (a lying tool's call ran twice, with one effect), emissionDeduped (a
 * rollback re-asked for an emission and the journal answered).
 */
module gateway {
  const CALLS: Set[int]
  const REMOTE: int -> bool     // the call's tool is reached across pf
  const EGRESS: int -> bool     // a hosted tool's server reaches out itself
  const ANNOT: int -> str       // its openWorldHint: "open" | "closed" | "none"
  const GATED: bool
  const DEFAULT_INTERNAL: bool
  pure val MAX_RUNS: int = 3

  var runs: int -> int          // how many times the call executed
  var asked: int -> int         // how many times the agent asked for it (rollbacks re-ask)
  var effects: int -> int       // outside effects of the call
  var viaOutbox: int -> int     // outside effects that left through an outbox
  var journal: Set[int]         // emissions the outbox has recorded

  def emission(c: int): bool =
    REMOTE.get(c) or ANNOT.get(c) == "open" or (ANNOT.get(c) == "none" and not(DEFAULT_INTERNAL))

  action init = all {
    runs' = CALLS.mapBy(_ => 0), asked' = CALLS.mapBy(_ => 0),
    effects' = CALLS.mapBy(_ => 0), viaOutbox' = CALLS.mapBy(_ => 0), journal' = Set(),
  }

  // The agent asks for call c: the first time, or again after a rollback.
  action call(c: int): bool = {
    val first = not(journal.contains(c))
    all {
      asked.get(c) < MAX_RUNS,
      asked' = asked.setBy(c, x => x + 1),
      if (emission(c) and not(first)) all {
        // answered from the outbox journal: nothing runs again
        runs' = runs, effects' = effects, viaOutbox' = viaOutbox, journal' = journal,
      } else {
        // it runs: a remote tool through the agent's outbox; a hosted one in its server
        val outside = REMOTE.get(c) or EGRESS.get(c)
        val gatedOnce = (REMOTE.get(c) or GATED) and effects.get(c) > 0
        val lands = outside and not(gatedOnce)
        all {
          runs' = runs.setBy(c, x => x + 1),
          effects' = if (lands) effects.setBy(c, x => x + 1) else effects,
          viaOutbox' = if (lands and (REMOTE.get(c) or GATED)) viaOutbox.setBy(c, x => x + 1) else viaOutbox,
          journal' = if (emission(c)) journal.union(Set(c)) else journal,
        }
      },
    }
  }

  action idle = all {
    runs' = runs, asked' = asked, effects' = effects, viaOutbox' = viaOutbox, journal' = journal,
  }

  action step = any {
    nondet c = oneOf(CALLS)
    call(c),
    idle,
  }

  // ---- properties -------------------------------------------------------------------------
  val atMostOnce: bool = CALLS.forall(c => effects.get(c) <= 1)
  val remoteViaOutbox: bool = CALLS.forall(c => REMOTE.get(c) implies viaOutbox.get(c) == effects.get(c))
  val safety: bool = atMostOnce and remoteViaOutbox

  // witnesses
  val misannotatedReplayed: bool = CALLS.exists(c =>
    ANNOT.get(c) == "closed" and EGRESS.get(c) and runs.get(c) > 1 and effects.get(c) == 1)
  val emissionDeduped: bool = CALLS.exists(c => emission(c) and asked.get(c) > 1 and runs.get(c) == 1)
}

// 1: remote, "open". 2: hosted, "closed", truly internal. 3: hosted,
// "closed", but its server reaches out (the annotation lies). 4: hosted,
// unannotated, reaches out.
module design {
  import gateway(CALLS = Set(1, 2, 3, 4),
                 REMOTE = Map(1 -> true, 2 -> false, 3 -> false, 4 -> false),
                 EGRESS = Map(1 -> false, 2 -> false, 3 -> true, 4 -> true),
                 ANNOT = Map(1 -> "open", 2 -> "closed", 3 -> "closed", 4 -> "none"),
                 GATED = true, DEFAULT_INTERNAL = false).* from "./gateway"
}

module ungated {
  import gateway(CALLS = Set(1, 2, 3, 4),
                 REMOTE = Map(1 -> true, 2 -> false, 3 -> false, 4 -> false),
                 EGRESS = Map(1 -> false, 2 -> false, 3 -> true, 4 -> true),
                 ANNOT = Map(1 -> "open", 2 -> "closed", 3 -> "closed", 4 -> "none"),
                 GATED = false, DEFAULT_INTERNAL = false).* from "./gateway"
}

// Only the unannotated tool can go wrong here (no lying annotation).
module missingIsInternal {
  import gateway(CALLS = Set(1, 2, 4),
                 REMOTE = Map(1 -> true, 2 -> false, 4 -> false),
                 EGRESS = Map(1 -> false, 2 -> false, 4 -> true),
                 ANNOT = Map(1 -> "open", 2 -> "closed", 4 -> "none"),
                 GATED = false, DEFAULT_INTERNAL = true).* from "./gateway"
}

// The design's default alone, without gating: correct annotations and the
// "missing means emission" rule keep effects single.
module honestUngated {
  import gateway(CALLS = Set(1, 2, 4),
                 REMOTE = Map(1 -> true, 2 -> false, 4 -> false),
                 EGRESS = Map(1 -> false, 2 -> false, 4 -> true),
                 ANNOT = Map(1 -> "open", 2 -> "closed", 4 -> "none"),
                 GATED = false, DEFAULT_INTERNAL = false).* from "./gateway"
}
