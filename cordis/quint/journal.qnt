// -*- mode: Bluespec; -*-
/**
 * journal: the outbox's journal as appended facts
 * (docs/cordis/rfc/0007-boundaries.md, "History: decisions and evidence are
 * append-only"; after casselc/observability's requirement R-L6 and its
 * near miss 49).
 *
 * Today's journal updates a row in place. As facts: each fact has a stable
 * id the writer gave it, a custody position (the order it was appended in),
 * a value, and the fact it **supersedes**. The attempt's current state is
 * the resolution of the chain: the fact nothing supersedes. Facts reach the
 * journal over at-least-once delivery, so one may arrive twice and a later
 * fact before an earlier one; precedence is the writer's, stated by
 * `supersedes`, never inferred from arrival.
 *
 * Accounting adjustments are ledger events with a stable id, applied to the
 * settled amount exactly once however often they are delivered.
 *
 * An audit names a **basis**: the store's identity and a commit position.
 * The view at a basis is the facts up to that position. A basis from
 * another store (the journal was restored from a backup, whose positions
 * are reused by different facts) is refused, and so is one at or below the
 * history horizon (compaction has taken those facts: above it, a checkpoint
 * stands for them), rather than answered with an incomplete history.
 *
 * Properties:
 *  - resolutionIntended: the resolved value is the one the writer's latest
 *    delivered fact (highest revision) carries;
 *  - settledMatches: the settled amount is the sum of the distinct
 *    adjustments delivered;
 *  - viewStable: a basis always shows the same resolved value;
 *  - viewNeverPartial: no view is answered for a basis at or below the
 *    horizon.
 * The model found, before this form, that a supersedes link alone is not
 * enough: fact 2 (superseding 1) arriving before fact 0, with 1 missing,
 * leaves 0 and 2 both unsuperseded among the facts present, and picking the
 * last arrival picks 0. Each fact therefore carries the writer's revision,
 * and the view above the history horizon is the resolved value, which a
 * checkpoint preserves (the facts listed would legitimately shrink).
 * Mutants:
 *  - LATEST_WINS (`latestWins`): resolve to the fact appended last (an
 *    older fact delivered late overrides a newer one) -> resolutionIntended;
 *  - ADJUST_AGAIN (`adjustAgain`): an adjustment is applied at every
 *    delivery -> settledMatches;
 *  - ANSWER_EXPIRED (`answerExpired`): a basis at or below the horizon is
 *    answered with the facts that survive -> viewNeverPartial;
 *  - IGNORE_STORE (`ignoreStore`): a basis from before a restore is
 *    answered from the new store -> viewStable.
 * Witnesses (checked as not(w), expected VIOLATED): reordered (a fact
 * arrived before its predecessor), retried (a delivery of a fact already
 * held), adjustmentRetried, refusedExpired, refusedForeign, answered.
 */
module journal {
  const LATEST_WINS: bool
  const ADJUST_AGAIN: bool
  const ANSWER_EXPIRED: bool
  const IGNORE_STORE: bool

  type Fact = { id: int, sup: int, rev: int, v: int, pos: int }
  type W = { id: int, sup: int, rev: int, v: int }
  type Basis = { store: int, pos: int }
  type View = { store: int, pos: int, value: int, partial: bool }

  pure val MAXW = 3       // facts the writer writes
  pure val MAXA = 2       // adjustments
  pure val MAXM = 3       // deliveries in flight

  type S = {
    facts: List[Fact], pos: int, store: int, horizon: int,
    written: List[W], inflight: List[int],
    deltas: List[int], seen: Set[int], adjInflight: List[int], settled: int,
    issued: List[Basis], views: List[View],
    reordered: int, retried: int, adjRetried: int, refusedExpired: int, refusedForeign: int, restores: int,
  }

  var s: S

  action init = s' = {
    facts: List(), pos: 0, store: 0, horizon: 0, written: List(), inflight: List(),
    deltas: List(), seen: Set(), adjInflight: List(), settled: 0, issued: List(), views: List(),
    reordered: 0, retried: 0, adjRetried: 0, refusedExpired: 0, refusedForeign: 0, restores: 0,
  }

  pure def removeAt(l: List[int], i: int): List[int] = l.slice(0, i).concat(l.slice(i + 1, l.length()))

  // ---- facts -------------------------------------------------------------------------------------
  // the writer writes a fact superseding its previous one, and sends it
  action write: bool = {
    nondet v = 1.to(3).oneOf()
    val n = s.written.length()
    all {
      n < MAXW, s.inflight.length() < MAXM,
      s' = { ...s, written: s.written.append({ id: n, sup: n - 1, rev: n, v: v }), inflight: s.inflight.append(n) },
    }
  }

  // the writer sends a fact again (at-least-once)
  action retry: bool = {
    nondet w = s.written.indices().oneOf()
    all { s.inflight.length() < MAXM, s' = { ...s, inflight: s.inflight.append(w) } }
  }

  // a fact arrives: appended once however often it arrives (a stable id)
  action deliver: bool = {
    nondet k = s.inflight.indices().oneOf()
    val w = s.written[s.inflight[k]]
    val held = s.facts.indices().exists(f => s.facts[f].id == w.id)
    val predecessorMissing = w.sup >= 0 and not(s.facts.indices().exists(f => s.facts[f].id == w.sup))
    s' = { ...s,
      inflight: removeAt(s.inflight, k),
      facts: if (held) s.facts else s.facts.append({ id: w.id, sup: w.sup, rev: w.rev, v: w.v, pos: s.pos + 1 }),
      pos: if (held) s.pos else s.pos + 1,
      retried: if (held) s.retried + 1 else s.retried,
      reordered: if (not(held) and predecessorMissing) s.reordered + 1 else s.reordered }
  }

  // ---- the ledger ---------------------------------------------------------------------------------
  action adjust: bool = {
    nondet d = 1.to(2).oneOf()
    all {
      s.deltas.length() < MAXA, s.adjInflight.length() < MAXM,
      s' = { ...s, deltas: s.deltas.append(d), adjInflight: s.adjInflight.append(s.deltas.length()) },
    }
  }

  action adjustRetry: bool = {
    nondet a = s.deltas.indices().oneOf()
    all { s.adjInflight.length() < MAXM, s' = { ...s, adjInflight: s.adjInflight.append(a) } }
  }

  // an adjustment arrives: applied once by its id (ADJUST_AGAIN: at every delivery)
  action adjustDeliver: bool = {
    nondet k = s.adjInflight.indices().oneOf()
    val a = s.adjInflight[k]
    val applied = not(s.seen.contains(a)) or ADJUST_AGAIN
    s' = { ...s,
      adjInflight: removeAt(s.adjInflight, k),
      seen: s.seen.union(Set(a)),
      settled: if (applied) s.settled + s.deltas[a] else s.settled,
      adjRetried: if (s.seen.contains(a)) s.adjRetried + 1 else s.adjRetried }
  }

  // ---- the store and audits -----------------------------------------------------------------------
  // a restore from a backup: the log is cut back to an earlier position and the store has a new identity;
  // the positions after it will be reused by other facts
  action restore: bool = {
    nondet r = 0.to(MAXW).oneOf()
    all {
      r < s.pos, s.restores < 1,
      s' = { ...s, facts: s.facts.select(f => f.pos <= r), pos: r, store: s.store + 1, horizon: if (s.horizon > r) r else s.horizon,
             restores: s.restores + 1 },
    }
  }

  // compaction: the history up to a horizon is replaced by a checkpoint
  action compact: bool = {
    nondet h = 0.to(MAXW).oneOf()
    all { h > s.horizon, h <= s.pos, s' = { ...s, horizon: h } }
  }

  // an audit is handed a basis: the store and the position now
  action takeBasis: bool = all { s.issued.length() < 3, s' = { ...s, issued: s.issued.append({ store: s.store, pos: s.pos }) } }

  // an audit asks for the view at a basis: refused if it is from another store or at or below the horizon
  action query: bool = {
    nondet b = s.issued.indices().oneOf()
    val bs = s.issued[b]
    val foreign = bs.store != s.store and not(IGNORE_STORE)
    val expired = bs.pos <= s.horizon and not(ANSWER_EXPIRED)
    // what the basis shows: the resolved value at its position (a checkpoint keeps the effect of compacted facts)
    val value = resolveOf(s.facts.select(f => f.pos <= bs.pos))
    s' = { ...s,
      views: if (foreign or expired) s.views else s.views.append({ store: bs.store, pos: bs.pos, value: value, partial: bs.pos <= s.horizon }),
      refusedForeign: if (foreign) s.refusedForeign + 1 else s.refusedForeign,
      refusedExpired: if (expired and not(foreign)) s.refusedExpired + 1 else s.refusedExpired }
  }

  action idle: bool = s' = s

  action step = any { write, retry, deliver, adjust, adjustRetry, adjustDeliver, restore, compact, takeBasis, query, idle }

  // ---- properties -------------------------------------------------------------------------------
  // the resolved value of some facts: among those nothing supersedes, the one of the highest revision, the
  // writer's own order (LATEST_WINS: the one appended last). A supersedes link alone cannot order facts across
  // a gap: with fact 1 not yet arrived, facts 0 and 2 both look current, and arrival order would pick 0
  pure def resolveOf(fs: List[Fact]): int = {
    val heads = fs.select(f => not(fs.indices().exists(g => fs[g].sup == f.id)))
    if (LATEST_WINS) (if (fs.length() == 0) -1 else fs[fs.length() - 1].v)
    else if (heads.length() == 0) -1 else heads.foldl(heads[0], (best, f) => if (f.rev > best.rev) f else best).v
  }
  def resolved: int = resolveOf(s.facts)
  // what the writer's latest delivered fact carries
  def intended: int = {
    val delivered = s.facts.foldl(-1, (m, f) => if (f.id > m) f.id else m)
    if (delivered < 0) -1 else s.written[delivered].v
  }
  val resolutionIntended: bool = resolved == intended
  val settledMatches: bool = s.settled == s.seen.fold(0, (t, a) => t + s.deltas[a])
  val viewStable: bool =
    s.views.indices().forall(i => s.views.indices().forall(j => (s.views[i].store == s.views[j].store and s.views[i].pos == s.views[j].pos) implies s.views[i].value == s.views[j].value))
  val viewNeverPartial: bool = s.views.indices().forall(i => not(s.views[i].partial))
  val safety: bool = resolutionIntended and settledMatches and viewStable and viewNeverPartial

  // witnesses
  val reorderedW: bool = s.reordered > 0
  val retriedW: bool = s.retried > 0
  val adjustmentRetriedW: bool = s.adjRetried > 0
  val refusedExpiredW: bool = s.refusedExpired > 0
  val refusedForeignW: bool = s.refusedForeign > 0
  val answeredW: bool = s.views.length() > 0
}
