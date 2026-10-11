// -*- mode: Bluespec; -*-
/**
 * observe: observational emissions (docs/cordis/rfc/0006-turn-runtime.md,
 * revision 2, "Observational emissions").
 *
 * DESIGN.md's emission rule is "reconcile, never blind retry": a lost
 * receipt puts the fiber in Uncertain until a reconcile read asks the
 * world whether the intent's key took effect. Most inference calls and
 * read-only tool calls cannot answer that question, and need not: their
 * only effect beyond the answer is spend. This model adds a second kind of
 * emission, declared by the intent kind and granted by policy:
 *
 *  - an **effectful** intent (Eff) keeps the rule: a lost receipt makes
 *    the fiber Uncertain, and only a reconcile moves it on;
 *  - an **observational** intent (Obs) may be sent again after a lost
 *    receipt, as a new attempt, each attempt under its own reservation of
 *    its worst-case cost. An abandoned attempt's reservation is held until
 *    its late answer settles it at its actual cost, or it expires and is
 *    charged at the worst case; its answer is never applied. Attempts are
 *    bounded; when they run out the fiber fails, as nothing is uncertain.
 *
 *  - a **keyed** intent (Key; RFC 0003 revision 4: an HTTP PUT or DELETE,
 *    or a POST carrying an idempotency key) is sent again after a lost
 *    receipt with the same identity, and the world applies it at most once
 *    by that identity: a resend of an intent that already took effect runs
 *    nothing and charges nothing. Each send reserves as an observational
 *    attempt does. When its sends run out it is Uncertain, not Failed,
 *    since it may have taken effect. A world that keeps keys answers a
 *    resend with the usage of its one generation, so a keyed intent's
 *    answer releases its other sends' reservations rather than charging
 *    them (the halo-flash run: 4096 charged for a lost send of a
 *    105-token generation).
 *
 * One fiber emits the intents KIND[0], KIND[1], ... in order. The world
 * may run an attempt or not, answer it or lose the answer, and answer
 * late. Real spend is a ghost: what the world actually charged.
 *
 * Properties:
 *  - effectOnce: an effectful or keyed intent takes effect at most once;
 *  - receiptOnce: one answer is applied per intent;
 *  - withinBudget: settled plus reserved spend never exceeds the budget;
 *  - accounted: real spend never exceeds settled plus reserved spend (a
 *    lost attempt is never assumed free);
 *  - obsNeverUncertain: an observational intent never makes the fiber
 *    Uncertain (the point of the class: no reconcile it cannot answer).
 * Mutants:
 *  - MISCLASSIFY (`misclassify`): an effectful kind is treated as
 *    observational (a cell, not policy, chose its class) -> effectOnce;
 *  - REFUND_LOST (`refundLost`): an expired attempt is refunded, not
 *    charged -> accounted;
 *  - NO_RESERVE (`noReserve`): an attempt is sent without a reservation,
 *    settled after -> withinBudget;
 *  - APPLY_LATE (`applyLate`): an abandoned attempt's late answer is also
 *    applied -> receiptOnce;
 *  - ONE_CLASS (`oneClass`): DESIGN.md's rule before this class, every
 *    lost receipt is Uncertain -> obsNeverUncertain (an inference call
 *    waits on a reconcile no provider can answer);
 *  - KEY_IGNORED (`keyIgnored`): the world does not deduplicate a keyed
 *    intent (a server that ignores the idempotency key, or a POST policy
 *    wrongly granted the class) -> effectOnce;
 *  - RELEASE_OBS (`releaseObs`): an observational intent's earlier attempts
 *    are released, not charged, when it is answered, as a keyed intent's
 *    are; but each of its attempts may have run -> accounted.
 * A world, not a mutant: GRACE_EXPIRY (`graceExpiry`) stops an abandoned
 * send's generation part way when its client has been gone longer than
 * the server's grace window, keeping nothing, so a resend generates afresh
 * and the stopped part's tokens are in no answer. A keyed answer releases
 * the other sends only when the world says it covers them (REPORTED, and
 * not fresh: halo-flash's Halo-Answer replayed or adopted); one generated
 * afresh, or one the world says nothing of (a world that keeps no keys
 * says nothing), charges them their worst case, or, with DISCARDED, what
 * the world says it discarded under the key (Halo-Discarded-Tokens).
 * `accounted` holds under grace expiry and a world that ignores keys
 * alike. Mutants (in observe_report.qnt): RELEASE_UNREPORTED, the rule
 * before Halo-Answer, releases an answer that says nothing ->
 * accounted (keyIgnoredRelease, graceExpiryRelease); UNDER_REPORT, the
 * world reports only the latest stopped send's tokens -> accounted
 * (underReport). The instances are in observe_modes.qnt and
 * observe_report.qnt, apart from this module, so each file stays under
 * Apalache's request limit.
 * Witnesses (checked as not(w), expected VIOLATED): retriedThenDone,
 * lateSettled, exhausted, effectfulUncertain, reconciled, keyedResent (a
 * keyed intent sent again completed), keyedDeduplicated (a resend found the
 * intent already applied), keyedUncertainW, supersededReleased (a reported
 * replay released the other sends), freshCharged, exactCharged (charged
 * what the world said it discarded, under the worst case).
 */
module observe {
  const MISCLASSIFY: bool
  const REFUND_LOST: bool
  const NO_RESERVE: bool
  const APPLY_LATE: bool
  const ONE_CLASS: bool
  const KEY_IGNORED: bool
  const RELEASE_OBS: bool
  const GRACE_EXPIRY: bool
  const REPORTED: bool
  const RELEASE_UNREPORTED: bool
  const DISCARDED: bool
  const UNDER_REPORT: bool

  type Kind = Obs | Eff | Key
  type Phase = Ready | Sent | Lost | Uncertain | Failed | Done

  pure val KIND: List[Kind] = [Obs, Eff, Key]
  pure val N = 3
  pure val R = 2          // an attempt's worst-case cost: its reservation
  pure val B = 9          // the fiber's budget
  pure val MAXA = 3       // attempts per observational intent
  pure val NONE = -1

  // how the runtime treats an intent: by its declared kind, unless misclassified
  pure def retryable(k: Kind): bool = (k == Obs and not(ONE_CLASS)) or MISCLASSIFY
  // sent again with the same identity after a lost receipt, while sends remain
  pure def resendable(k: Kind): bool = k == Key and not(ONE_CLASS)

  // kept: the world holds the attempt's generation, to answer a resend under the same key from it
  // fresh: it ran as a generation of its own, not answered from a kept one
  type Attempt = { intent: int, n: int, ran: bool, cost: int, live: bool, kept: bool, fresh: bool }

  type S = {
    pc: int,                 // the intent being emitted
    phase: Phase,
    tries: int,              // attempts of the current intent
    cur: int,                // the current attempt's index in [attempts], or NONE
    attempts: List[Attempt], // every attempt sent; live while its answer may come
    reserved: int,
    settled: int,
    real: int,               // ghost: what the world charged
    effects: List[int],      // ghost: per intent, times it took effect
    applied: List[int],      // per intent, answers applied
    lates: int, retries: int, reconciles: int,
    resent: int, deduped: int, keyedUncertain: bool, released: int, charged: int, exact: int,
  }

  var s: S

  action init = s' = {
    pc: 0, phase: Ready, tries: 0, cur: NONE, attempts: List(),
    reserved: 0, settled: 0, real: 0, effects: [0, 0, 0], applied: [0, 0, 0],
    lates: 0, retries: 0, reconciles: 0,
    resent: 0, deduped: 0, keyedUncertain: false, released: 0, charged: 0, exact: 0,
  }

  pure def inc(l: List[int], i: int): List[int] = l.replaceAt(i, l[i] + 1)

  // send an attempt of the current intent, under a reservation of R
  action send: bool = all {
    s.pc < N,
    s.phase == Ready or (s.phase == Lost and s.tries < MAXA),
    NO_RESERVE or s.settled + s.reserved + R <= B,
    s' = { ...s,
      phase: Sent, tries: s.tries + 1, cur: s.attempts.length(),
      attempts: s.attempts.append({ intent: s.pc, n: s.tries, ran: false, cost: 0, live: true, kept: false, fresh: false }),
      reserved: if (NO_RESERVE) s.reserved else s.reserved + R,
      retries: if (s.phase == Lost) s.retries + 1 else s.retries,
      resent: if (s.phase == Lost and KIND[s.pc] == Key) s.resent + 1 else s.resent,
    },
  }

  // the world runs a live attempt, at most once, charging 1..R
  action worldRuns: bool = {
    nondet a = s.attempts.indices().oneOf()
    nondet c = 1.to(R).oneOf()
    val at = s.attempts[a]
    // a keyed intent already applied: the world answers from what it kept, running and charging
    // nothing, and reports the usage of the one generation it kept (as halo-flash's replay does)
    val firsts = s.attempts.indices().filter(b => b != a and s.attempts[b].intent == at.intent and s.attempts[b].kept)
    val dedup = KIND[at.intent] == Key and not(KEY_IGNORED) and firsts.size() > 0
    val cost = if (dedup) firsts.fold(0, (m, b) => if (s.attempts[b].cost > m) s.attempts[b].cost else m) else c
    all {
      at.live, not(at.ran),
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, ran: true, cost: cost, kept: true, fresh: not(dedup) }),
        real: if (dedup) s.real else s.real + cost,
        effects: if (KIND[at.intent] != Obs and not(dedup)) inc(s.effects, at.intent) else s.effects,
        deduped: if (dedup) s.deduped + 1 else s.deduped,
      },
    }
  }

  // settle an attempt's reservation at its actual cost
  def settle(at: Attempt): (int, int) =
    (if (NO_RESERVE) s.reserved else s.reserved - R, s.settled + at.cost)

  // an answer arrives for a live attempt that ran
  // a keyed intent's answer covers every send of it (the world generated it once, and reports that
  // generation's usage): the other sends' reservations are released, not charged (RELEASE_OBS also
  // releases an observational intent's, whose attempts each ran)
  def releasable(i: int): bool = KIND[i] == Key or (RELEASE_OBS and KIND[i] == Obs)
  def others(a: int): Set[int] =
    s.attempts.indices().filter(b => b != a and s.attempts[b].intent == s.attempts[a].intent and s.attempts[b].live)

  action answer: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    val current = a == s.cur and s.phase == Sent
    val st = settle(at)
    val closed = if (current and releasable(at.intent)) others(a) else Set()
    // REPORTED: the answer says whether it came from a fresh generation (halo-flash's Halo-Answer:
    // generated); then the other sends may have spent tokens no answer reports, and are charged; an
    // answer replayed or adopted releases them. An answer that says nothing is charged too (a world
    // that keeps no keys says nothing), unless RELEASE_UNREPORTED (the rule before Halo-Answer).
    val charge = if (REPORTED) at.fresh else not(RELEASE_UNREPORTED)
    val release = if (charge) Set() else closed
    // DISCARDED: a fresh answer also says how many tokens the world discarded under the key (halo-
    // flash's Halo-Discarded-Tokens): every other send that ran as a generation of its own, stopped;
    // they are charged that, not their worst case. UNDER_REPORT: it says only the latest one's.
    val gens = closed.filter(b => s.attempts[b].ran and s.attempts[b].fresh)
    val discarded =
      if (UNDER_REPORT) gens.fold(0, (m, b) => if (s.attempts[b].cost > m) s.attempts[b].cost else m)
      else gens.fold(0, (t, b) => t + s.attempts[b].cost)
    val amount = if (REPORTED and DISCARDED) discarded else R * closed.size()
    all {
      at.live, at.ran,
      s' = { ...s,
        attempts: s.attempts.indices().fold(s.attempts, (l, b) =>
          if (b == a or closed.contains(b)) l.replaceAt(b, { ...l[b], live: false }) else l),
        reserved: if (NO_RESERVE) st._1 else st._1 - R * closed.size(),
        settled: if (charge) st._2 + amount else st._2,
        charged: s.charged + (if (charge) closed.size() else 0),
        exact: s.exact + (if (charge and amount < R * closed.size()) 1 else 0),
        released: s.released + release.size(),
        pc: if (current) s.pc + 1 else s.pc,
        phase: if (current) Ready else s.phase,
        tries: if (current) 0 else s.tries,
        cur: if (current) NONE else s.cur,
        applied: if (current or APPLY_LATE) inc(s.applied, at.intent) else s.applied,
        lates: if (current) s.lates else s.lates + 1,
      },
    }
  }

  // the current attempt's answer is lost (a timeout): an observational
  // intent may try again; an effectful one is Uncertain
  action lose: bool = all {
    s.phase == Sent,
    s' = { ...s,
      phase: if (retryable(KIND[s.pc])) (if (s.tries < MAXA) Lost else Failed)
             else if (resendable(KIND[s.pc]) and s.tries < MAXA) Lost
             else Uncertain,
      cur: NONE,
      keyedUncertain: s.keyedUncertain or (KIND[s.pc] == Key and not(s.tries < MAXA)),
    },
  }

  // an abandoned attempt will never answer: charge it at the worst case (a keyed intent's sends are
  // held until the intent is answered, which releases them, or given up)
  action expire: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.live, a != s.cur,
      not(releasable(at.intent)) or at.intent < s.pc or s.phase == Failed,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, live: false }),
        reserved: if (NO_RESERVE) s.reserved else s.reserved - R,
        settled: if (REFUND_LOST) s.settled else s.settled + R,
      },
    }
  }

  // an effectful intent is reconciled: the outbox asks the world by key,
  // fencing the key, and the reservation settles at what the world reports
  action reconcile: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    // asked by key, the world reports whether any send of the intent took effect
    val took = s.attempts.indices().exists(b => s.attempts[b].intent == s.pc and s.attempts[b].ran)
    all {
      s.phase == Uncertain, at.intent == s.pc, at.live,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, live: false }),
        reserved: if (NO_RESERVE) s.reserved else s.reserved - R,
        settled: s.settled + (if (at.ran) at.cost else 0),
        pc: if (took) s.pc + 1 else s.pc,
        phase: if (took) Ready else Failed,
        tries: 0,
        applied: if (took) inc(s.applied, at.intent) else s.applied,
        reconciles: s.reconciles + 1,
      },
    }
  }

  action idle: bool = s' = s

  // GRACE_EXPIRY: the world stops an abandoned send's generation part way (its client left more than
  // a grace window ago, and it had not finished), keeping nothing: its tokens were spent, and no answer
  // reports them, so a resend generates afresh
  action worldStops: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      GRACE_EXPIRY, at.ran, at.kept, at.live, a != s.cur,
      s' = { ...s, attempts: s.attempts.replaceAt(a, { ...at, kept: false }) },
    }
  }

  action step = any { send, worldRuns, answer, lose, expire, reconcile, worldStops, idle }

  // ---- properties -------------------------------------------------------------------------
  val effectOnce: bool = 0.to(N - 1).forall(i => KIND[i] != Obs implies s.effects[i] <= 1)
  val receiptOnce: bool = s.applied.foldl(true, (ok, n) => ok and n <= 1)
  val withinBudget: bool = s.settled + s.reserved <= B
  val accounted: bool = s.real <= s.settled + s.reserved
  val obsNeverUncertain: bool = not(s.phase == Uncertain and s.pc < N and KIND[s.pc] == Obs)
  val safety: bool = effectOnce and receiptOnce and withinBudget and accounted and obsNeverUncertain

  // witnesses
  val retriedThenDone: bool = s.retries > 0 and s.pc == N
  val lateSettled: bool = s.lates > 0
  val exhausted: bool = s.phase == Failed and s.pc < N and KIND[s.pc] == Obs
  val effectfulUncertain: bool = s.phase == Uncertain
  val reconciled: bool = s.reconciles > 0 and s.pc > 1
  val keyedResent: bool = s.resent > 0 and s.pc == N
  val keyedDeduplicated: bool = s.deduped > 0
  val keyedUncertainW: bool = s.keyedUncertain
  val supersededReleased: bool = s.released > 0
  val freshCharged: bool = s.charged > 0
  val exactCharged: bool = s.exact > 0
}
